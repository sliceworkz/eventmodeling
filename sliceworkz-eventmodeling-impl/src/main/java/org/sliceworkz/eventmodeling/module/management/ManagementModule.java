/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventmodeling.module.management;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.InstructionOutcome;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.management.ManagementInstruction;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextImpl;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;

/**
 * Reads {@link ManagementInstruction}s off the management stream and applies the ones addressed to
 * this instance through the bounded context's own admin surface — the remote half of
 * {@code AutomationAdminCapability}, {@code ProcessorAdminCapability} and the lifecycle, which
 * themselves stay strictly local.
 * <p>
 * <strong>Subscribed at the head, never bookmarked.</strong> {@link #start()} takes the stream's
 * {@code head()} and subscribes a projector starting after it, so only instructions appended from
 * then on are seen. Instructions are things an operator did rather than standing rules; a process
 * coming up that replayed all of them would stop and start things on the strength of last week's
 * decisions. The alternative — a bookmark per instance — loses on both counts: a fresh process is a
 * new bookmark reader anyway (the process id is minted per JVM run), and an instruction meant for
 * a moment that has passed is worse applied late than not at all. The cost is stated on the
 * instruction type: an instruction issued while this instance was down is not seen by it, which is
 * what the missing acknowledgement says.
 * <p>
 * <strong>Applied one at a time, in stream order, off the notification path.</strong> The projector
 * runs on the store's notification thread, and the store serialises deliveries to one subscriber,
 * so nothing here races itself; but starting a context blocks until its ephemeral read models are
 * projected, and that is not a wait to impose on the store's notification machinery. So the
 * projection only <em>queues</em> the instruction onto a single virtual thread of this module's own,
 * which applies it and answers. A throw out of applying it is contained there and answered as
 * {@code FAILED}, so one bad instruction never ends the subscription.
 * <p>
 * <strong>Every addressed instruction is answered, and no unaddressed one is.</strong> The answer
 * is a {@link BoundedContextEvent.InstructionHandled} emitted through the same emitter as every
 * other kernel event, under the instruction's tracing — so it carries the instruction's correlation
 * id and actor, on this instance's tags. An operator matching answers to instructions counts one per
 * instance the target named; an instance the target did not name says nothing, since a target
 * naming a whole context would otherwise draw a chorus of "not for me" from every instance.
 * <p>
 * Survives {@code stop()} deliberately: a stopped context that stopped listening could never be told
 * to start again. Only {@code terminate()} ends it, by closing the stream — which is why the builder
 * asks for a stream of the context's own.
 */
public class ManagementModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(ManagementModule.class);

	private final String boundedContext;
	private final Instance instance;
	private final EventStream<ManagementInstruction> instructions;
	private final BoundedContextEventEmitter eventEmitter;

	private BoundedContextImpl<?,?,?> context;
	private ExecutorService executor;
	private boolean subscribed;
	private boolean terminated;

	public ManagementModule ( String boundedContext, Instance instance, EventStream<ManagementInstruction> instructions, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.instructions = instructions;
		this.eventEmitter = eventEmitter;
	}

	/** The context whose admin surface the instructions are applied through; only known once it is built. */
	public void attach ( BoundedContextImpl<?,?,?> context ) {
		this.context = context;
	}

	/**
	 * Subscribes to the instruction stream from its current head. Idempotent, so the context's
	 * {@code start()} can call it on every start and the subscription is made once.
	 */
	public synchronized void start ( ) {
		if ( subscribed || terminated ) {
			return;
		}
		if ( context == null ) {
			throw new IllegalStateException("management module of '" + boundedContext + "' started before it was attached to its context");
		}
		executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("bc-management/" + boundedContext).factory());
		// null when the stream is empty: then there is nothing to skip, and starting from the
		// beginning is starting from the head
		EventReference head = instructions.head().orElse(null);
		Projector.from(instructions)
				.towards(new InstructionProjection())
				.startingAfter(head)
				.subscribe()
				.build();
		subscribed = true;
		LOGGER.info("bounded context '{}' takes management instructions from {} (after {})", boundedContext, instructions.id(), head);
	}

	/** Ends the subscription and the thread applying instructions. Terminal. */
	public synchronized void terminate ( ) {
		if ( terminated ) {
			return;
		}
		terminated = true;
		if ( subscribed ) {
			// the stream is ours by contract (see the builder's javadoc), so closing it ends exactly
			// our subscription; the handle stays usable, which is all the store promises
			instructions.close();
			executor.shutdownNow();
		}
	}

	/** Queues every instruction that names this instance; ignores the rest without a word. */
	private class InstructionProjection implements Projection<ManagementInstruction> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		public void when ( Event<ManagementInstruction> event ) {
			ManagementInstruction instruction = event.data();
			if ( instruction == null || instruction.target() == null || !instruction.target().matches(boundedContext, instance) ) {
				return;
			}
			// the instruction's actor, channel and correlation id travel onto the answer; the
			// emitter puts this instance's own tags on it regardless of whose the instruction carried
			Tracing tracing = Tracing.readFrom(event);
			executor.execute(() -> handle(instruction, tracing));
		}
	}

	private void handle ( ManagementInstruction instruction, Tracing tracing ) {
		String type = instruction.getClass().getSimpleName();
		String subject = subjectOf(instruction);
		try {
			InstructionOutcome outcome = apply(instruction, tracing);
			LOGGER.info("management instruction {} for {} on bounded context '{}': {}", type, subject, boundedContext, outcome);
			answer(type, subject, outcome, null, tracing);
		} catch ( IllegalArgumentException rejection ) {
			// the admin capabilities' verdict on a name this instance does not have: not an error of
			// ours -- a target naming a whole context reaches instances deploying different slices
			LOGGER.warn("management instruction {} for {} rejected on bounded context '{}': {}", type, subject, boundedContext, rejection.getMessage());
			answer(type, subject, InstructionOutcome.REJECTED, rejection.getMessage(), tracing);
		} catch ( RuntimeException failure ) {
			LOGGER.error("management instruction {} for {} failed on bounded context '{}'", type, subject, boundedContext, failure);
			answer(type, subject, InstructionOutcome.FAILED, failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage(), tracing);
		}
	}

	private InstructionOutcome apply ( ManagementInstruction instruction, Tracing tracing ) {
		return switch ( instruction ) {
			case ManagementInstruction.StopAutomation i -> changed(context.stopAutomation(i.automation()));
			case ManagementInstruction.StartAutomation i -> changed(context.restartAutomation(i.automation()));
			case ManagementInstruction.StopProcessor i -> changed(context.stopProcessor(i.kind(), i.name()));
			case ManagementInstruction.StartProcessor i -> changed(context.restartProcessor(i.kind(), i.name()));
			case ManagementInstruction.StopBoundedContext i -> {
				if ( !context.isStarted() ) {
					yield InstructionOutcome.NO_CHANGE;
				}
				context.stop();
				yield InstructionOutcome.APPLIED;
			}
			case ManagementInstruction.StartBoundedContext i -> {
				if ( context.isStarted() ) {
					yield InstructionOutcome.NO_CHANGE;
				}
				context.start(); // blocks until the ephemeral read models are projected: this thread is ours
				yield InstructionOutcome.APPLIED;
			}
			case ManagementInstruction.ReportStatus i -> {
				eventEmitter.emit(new BoundedContextEvent.InstanceStatusReported(boundedContext, context.automations(), context.processors()), tracing);
				yield InstructionOutcome.APPLIED;
			}
		};
	}

	private static InstructionOutcome changed ( boolean changed ) {
		return changed ? InstructionOutcome.APPLIED : InstructionOutcome.NO_CHANGE;
	}

	private String subjectOf ( ManagementInstruction instruction ) {
		return switch ( instruction ) {
			case ManagementInstruction.StopAutomation i -> i.automation();
			case ManagementInstruction.StartAutomation i -> i.automation();
			case ManagementInstruction.StopProcessor i -> i.kind() + "/" + i.name();
			case ManagementInstruction.StartProcessor i -> i.kind() + "/" + i.name();
			case ManagementInstruction.StopBoundedContext i -> boundedContext;
			case ManagementInstruction.StartBoundedContext i -> boundedContext;
			case ManagementInstruction.ReportStatus i -> boundedContext;
		};
	}

	private void answer ( String type, String subject, InstructionOutcome outcome, String detail, Tracing tracing ) {
		eventEmitter.emit(new BoundedContextEvent.InstructionHandled(boundedContext, type, subject, outcome, detail), tracing);
	}

}
