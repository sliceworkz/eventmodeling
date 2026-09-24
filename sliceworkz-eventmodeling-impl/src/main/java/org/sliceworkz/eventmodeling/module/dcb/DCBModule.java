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
package org.sliceworkz.eventmodeling.module.dcb;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.boundedcontext.BoundedContextEventEmitter;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

public class DCBModule<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> implements LifecycleCapability {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(DCBModule.class);
	
	private String boundedContext;
	private Instance instance;

	private ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule;
	private EventStream<DOMAIN_EVENT_TYPE> domainEventStream;
	private EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream;

	private final BoundedContextObserver observer;

	private final BoundedContextEventEmitter eventEmitter;

	public DCBModule ( String boundedContext, Instance instance, ReadModelModule<DOMAIN_EVENT_TYPE> readModelModule, EventStream<DOMAIN_EVENT_TYPE> domainEventStream, EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream, BoundedContextObserver observer, BoundedContextEventEmitter eventEmitter ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
		this.readModelModule = readModelModule;
		this.domainEventStream = domainEventStream;
		this.outboundEventStream = outboundEventStream;
		this.observer = observer;
		this.eventEmitter = eventEmitter;
	}
	
	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, Tracing tracing ) {
		return executeAbstractCommand(command.commandName(), command.getClass(), command::execute, tracing, domainEventStream, false, null);
	}

	public Optional<EventReference> execute ( Command<DOMAIN_EVENT_TYPE> command, String idempotencyKey, Tracing tracing ) {
		return executeAbstractCommand(command.commandName(), command.getClass(), command::execute, tracing, domainEventStream, false, idempotencyKey);
	}

	public Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> command, Tracing tracing ) {
		return executeAbstractCommand(command.commandName(), command.getClass(), command::execute, tracing, outboundEventStream, true, null);
	}

	public Optional<EventReference> execute ( OutboundCommand<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> command, String idempotencyKey, Tracing tracing ) {
		return executeAbstractCommand(command.commandName(), command.getClass(), command::execute, tracing, outboundEventStream, true, idempotencyKey);
	}

	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Tracing tracing ) {
		return executeCommandWithResult(command, tracing, null);
	}

	public <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> execute ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, String idempotencyKey, Tracing tracing ) {
		return executeCommandWithResult(command, tracing, idempotencyKey);
	}

	/**
	 * Shared execution path for both command shapes. The command body arrives as a consumer of the
	 * context implementation rather than as the command itself, because since the two permits were
	 * given different context types ({@code CommandContext} vs the narrower
	 * {@code OutboundCommandContext}), there is no common {@code execute} left on
	 * {@code AbstractCommand} to call — {@code DCBCommandContextImpl} implements both, so a method
	 * reference to either shape's {@code execute} fits here.
	 */
	private <PRODUCED_EVENT_TYPE> Optional<EventReference> executeAbstractCommand ( String commandName, Class<?> commandClass, Consumer<DCBCommandContextImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE>> commandBody, Tracing tracing, EventStream<PRODUCED_EVENT_TYPE> targetEventStream, boolean outboundTarget, String idempotencyKey ) {
		Tracing tracingWithCommand = tracing.command(commandName);
		Observation.Target target = outboundTarget ? Observation.Target.OUTBOUND : Observation.Target.DOMAIN;
		try ( Observation.Scope<Outcome.CommandOutcome> scope = observer.start(new Observation.CommandExecution(boundedContext, commandName, commandClass, target, tracingWithCommand)) ) {
			long start = System.currentTimeMillis();

			DCBCommandContextImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> commandContext = new DCBCommandContextImpl<>(boundedContext, readModelModule, domainEventStream, targetEventStream, tracingWithCommand);
			try {
				commandBody.accept(commandContext);
				CommandResultImpl<DOMAIN_EVENT_TYPE,PRODUCED_EVENT_TYPE> commandResult = commandContext.getCommandResult();

				List<EventReference> eventReferences = persist(commandResult, targetEventStream, commandName, idempotencyKey, outboundTarget);

				emitCommandExecuted(commandContext, commandName, commandClass, start, eventReferences);
				scope.completed(new Outcome.Executed(raisedPerType(commandResult), eventReferences));

				return eventReferences.isEmpty() ? Optional.empty() : Optional.of(eventReferences.get(eventReferences.size() - 1));
			} catch ( OptimisticLockingException ole ) {
				emitCommandFailedOnOptimisticLocking(commandContext, commandName, commandClass, start, ole);
				scope.completed(conflictOf(ole));
				throw ole;
			} catch ( BusinessException rejection ) {
				emitCommandRejected(commandContext, commandName, commandClass, start, rejection);
				scope.completed(new Outcome.Rejected(rejection.getMessage()));
				throw rejection;
			} catch ( RuntimeException e ) {
				emitCommandFailed(commandContext, commandName, commandClass, start, e);
				scope.failed(e);
				throw e;
			}
		}
	}

	private <RESPONSE_TYPE> CommandExecutionResult<RESPONSE_TYPE> executeCommandWithResult ( CommandWithResult<DOMAIN_EVENT_TYPE, RESPONSE_TYPE> command, Tracing tracing, String idempotencyKey ) {
		String commandName = command.commandName();
		Tracing tracingWithCommand = tracing.command(commandName);
		try ( Observation.Scope<Outcome.CommandOutcome> scope = observer.start(new Observation.CommandExecution(boundedContext, commandName, command.getClass(), Observation.Target.DOMAIN, tracingWithCommand)) ) {
			long start = System.currentTimeMillis();

			DCBCommandContextImpl<DOMAIN_EVENT_TYPE,DOMAIN_EVENT_TYPE> commandContext = new DCBCommandContextImpl<>(boundedContext, readModelModule, domainEventStream, domainEventStream, tracingWithCommand);
			try {
				RESPONSE_TYPE response = command.execute(commandContext);
				CommandResultImpl<DOMAIN_EVENT_TYPE,DOMAIN_EVENT_TYPE> commandResult = commandContext.getCommandResult();

				List<EventReference> eventReferences = persist(commandResult, domainEventStream, commandName, idempotencyKey, false);

				emitCommandExecuted(commandContext, commandName, command.getClass(), start, eventReferences);
				scope.completed(new Outcome.Executed(raisedPerType(commandResult), eventReferences));

				Optional<EventReference> eventReference = eventReferences.isEmpty() ? Optional.empty() : Optional.of(eventReferences.get(eventReferences.size() - 1));
				return new CommandExecutionResult<>(eventReference, response);
			} catch ( OptimisticLockingException ole ) {
				emitCommandFailedOnOptimisticLocking(commandContext, commandName, command.getClass(), start, ole);
				scope.completed(conflictOf(ole));
				throw ole;
			} catch ( BusinessException rejection ) {
				emitCommandRejected(commandContext, commandName, command.getClass(), start, rejection);
				scope.completed(new Outcome.Rejected(rejection.getMessage()));
				throw rejection;
			} catch ( RuntimeException e ) {
				emitCommandFailed(commandContext, commandName, command.getClass(), start, e);
				scope.failed(e);
				throw e;
			}
		}
	}

	private <PRODUCED_EVENT_TYPE> List<EventReference> persist ( CommandResultImpl<DOMAIN_EVENT_TYPE, PRODUCED_EVENT_TYPE> commandResult, EventStream<PRODUCED_EVENT_TYPE> targetEventStream, String commandName, String idempotencyKey, boolean outboundTarget ) {
		// resolve and apply idempotency key (internal strategy vs external key)
		String resolvedKey = commandResult.resolveIdempotencyKey(idempotencyKey);
		if ( resolvedKey != null ) {
			commandResult.applyIdempotencyKey(resolvedKey);
		}

		// an outbound event without an idempotency key is a duplicate publication waiting for its
		// first at-least-once retry, so it is rejected here, before anything is stored — after the
		// key application above, so a key from any source satisfies it. forbidIdempotencyKey() is
		// the deliberate opt-out.
		if ( outboundTarget ) {
			commandResult.requireIdempotencyKeysOnOutboundEvents(commandName);
		}

		if ( !commandResult.raisedEvents().isEmpty() ) {
			// append to the event store (with optimistic locking the DCB way)
			// and return the last event reference produced (for bookmarking purposes etc ...)
			return targetEventStream.append(commandResult.appendCriteria(), commandResult.raisedEvents())
					.stream().map(Event::reference).toList();
		} else {
			LOGGER.debug("no events raised by command {}", commandName);
			return List.of();
		}
	}

	/** How many events of each type a command raised, by the name each is stored under. */
	private static Map<EventType,Integer> raisedPerType ( CommandResultImpl<?,?> commandResult ) {
		Map<EventType,Integer> raisedPerType = new LinkedHashMap<>();
		for ( EphemeralEvent<?> event : commandResult.raisedEvents() ) {
			raisedPerType.merge(EventType.of(event.data().getClass()), 1, Integer::sum);
		}
		return raisedPerType;
	}

	private static Outcome.Conflicted conflictOf ( OptimisticLockingException ole ) {
		Optional<EventReference> expected = ole.getExpectedLastEventReference();
		return new Outcome.Conflicted(expected != null ? expected : Optional.empty());
	}

	private void emitCommandExecuted ( DCBCommandContextImpl<?,?> commandContext, String commandName, Class<?> commandClass, long start, List<EventReference> eventReferences ) {
		emitOutcome(commandContext, commandClass, start, (metrics, slice) ->
			new BoundedContextEvent.CommandExecuted(boundedContext, commandName, eventReferences, metrics, slice));
	}

	private void emitCommandFailedOnOptimisticLocking ( DCBCommandContextImpl<?,?> commandContext, String commandName, Class<?> commandClass, long start, OptimisticLockingException ole ) {
		EventReference expectedLastEvent = ole.getExpectedLastEventReference() != null ? ole.getExpectedLastEventReference().orElse(null) : null;
		emitOutcome(commandContext, commandClass, start, (metrics, slice) ->
			new BoundedContextEvent.CommandFailedOnOptimisticLocking(boundedContext, commandName, expectedLastEvent, metrics, slice));
	}

	/**
	 * A business rejection is reported without a {@link BoundedContextEvent.Failure}: the reason is the
	 * message, and a stack trace for an outcome the command was written to produce would only say
	 * where in the command the rule sits. The three catch clauses above are ordered from the most
	 * specific outcome to the catch-all, and a {@code BusinessException} is only recognised as the
	 * exception the command threw — one wrapped by the projector (a rule thrown from a decision
	 * model's {@code when}) falls through to {@link #emitCommandFailed}, deliberately.
	 */
	private void emitCommandRejected ( DCBCommandContextImpl<?,?> commandContext, String commandName, Class<?> commandClass, long start, BusinessException rejection ) {
		String reason = rejection.getMessage();
		emitOutcome(commandContext, commandClass, start, (metrics, slice) ->
			new BoundedContextEvent.CommandRejected(boundedContext, commandName, reason, metrics, slice));
	}

	private void emitCommandFailed ( DCBCommandContextImpl<?,?> commandContext, String commandName, Class<?> commandClass, long start, Throwable failure ) {
		BoundedContextEvent.Failure failureInfo = failureOf(failure);
		emitOutcome(commandContext, commandClass, start, (metrics, slice) ->
			new BoundedContextEvent.CommandFailed(boundedContext, commandName, failureInfo, metrics, slice));
	}

	/**
	 * Emits the per-decision-model {@link BoundedContextEvent.DecisionModelProjected} events (the reads
	 * the command performed, which happen on both the success and failure paths) followed by the
	 * terminal command outcome produced by {@code terminal}. Does nothing when no listener is registered.
	 * <p>
	 * For each {@code DecisionModelProjected}, {@code eventsStreamed} is the physical read the model was
	 * projected from (shared by models read together through one merged query) and {@code eventsHandled}
	 * the subset relevant to that model.
	 */
	private void emitOutcome ( DCBCommandContextImpl<?,?> commandContext, Class<?> commandClass, long start,
			java.util.function.BiFunction<BoundedContextEvent.Metrics, BoundedContextEvent.FeatureSlice, BoundedContextEvent> terminal ) {
		if ( !eventEmitter.enabled() ) {
			return;
		}
		long duration = System.currentTimeMillis() - start;
		ProjectorMetrics projectorMetrics = commandContext.projectorMetrics();

		for ( DCBCommandContextImpl.DecisionModelProjection projection : commandContext.decisionModelProjections() ) {
			BoundedContextEvent.Metrics dmMetrics = new BoundedContextEvent.Metrics(projection.durationMs(), projection.queriesDone(), projection.eventsStreamed(), projection.eventsHandled(), projection.until());
			eventEmitter.emit(new BoundedContextEvent.DecisionModelProjected(boundedContext, projection.decisionModelClass().getSimpleName(), dmMetrics, eventEmitter.sliceFor(projection.decisionModelClass())), commandContext.tracing());
		}

		BoundedContextEvent.Metrics metrics = new BoundedContextEvent.Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
		eventEmitter.emit(terminal.apply(metrics, eventEmitter.sliceFor(commandClass)), commandContext.tracing());
	}

	private static BoundedContextEvent.Failure failureOf ( Throwable failure ) {
		java.io.StringWriter stackTrace = new java.io.StringWriter();
		failure.printStackTrace(new java.io.PrintWriter(stackTrace));
		return new BoundedContextEvent.Failure(failure.getClass().getName(), failure.getMessage(), stackTrace.toString());
	}
	
	@Override
	public void start ( ) {

	}

	@Override
	public void stop ( ) {

	}
	
	@Override
	public void terminate ( ) {
	}

}