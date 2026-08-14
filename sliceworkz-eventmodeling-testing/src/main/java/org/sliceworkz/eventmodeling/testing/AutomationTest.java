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
package org.sliceworkz.eventmodeling.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.AutomationFailureAction;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.automation.AutomationBatch;
import org.sliceworkz.eventmodeling.module.automation.AutomationContextImpl;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

/**
 * Base for testing an {@link Automation} together with its
 * {@link org.sliceworkz.eventmodeling.automation.TodoListReadModel}: seed domain events, assert what
 * the todo list makes of them, run batches, and assert what each batch appended — all synchronously
 * on the test thread, deterministic, no polling and no waits.
 * <pre>{@code
 * class ExecutePaymentAutomationTest extends AutomationTest<PaymentToExecute, PaymentsDomainEvent, Void, PaymentsOutboundEvent> {
 *
 *     private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();
 *
 *     @Override
 *     public Automation<PaymentToExecute, PaymentsDomainEvent, PaymentsOutboundEvent> automation ( ) {
 *         return new ExecutePaymentAutomation(new PaymentsToExecuteTodoList(), gateway);
 *     }
 *
 *     @Test
 *     void aPaymentIsExecutedAndLeavesTheTodoList ( ) {
 *         given(new PaymentRequested("p1", "BE68...", 100))
 *             .expectTodoItems(new PaymentToExecute("p1", "BE68...", 100))
 *             .whenBatchRuns()
 *             .itemsHandled(1)
 *             .and()
 *             .expectNoTodoItems();
 *     }
 * }
 * }</pre>
 *
 * <h2>How a round works, and why it matches production</h2>
 * The batch loop is not a re-implementation: {@link TestDefinition#whenBatchRuns()} runs
 * {@code AutomationBatch.handleBatch} — literally the code {@code AutomationProcessor} runs in
 * production — over a real {@link AutomationContext} on a real bounded context, so
 * {@code publishAndRecord}, idempotency keys and {@link Automation#onFailure} behave exactly as
 * deployed. Around that loop, the harness does what the processor does, synchronously:
 * <ol>
 * <li><b>Catch up the todo list</b> — the seeded events (and everything earlier batches appended) are
 * projected into the todo list. In production the todo list's own projector does this on its thread,
 * and the automation waits for it; here it happens inline.</li>
 * <li><b>Run exactly one batch</b> — up to {@link Automation#batchSize()} items, one at a time, each
 * failure routed through {@code onFailure}.</li>
 * <li><b>Deliberately no projection after the batch.</b> The events a batch raised reach the todo
 * list at the start of the <em>next</em> round, exactly as in production — which is what makes
 * at-least-once delivery expressible: {@link TestDefinition#whenItemsAreRedelivered()} is the same round
 * <em>without</em> step 1, so the todo list still offers the items the previous batch already handled.
 * That is the crash-between-append-and-bookmark case, and
 * {@code whenItemsAreRedelivered().noEvents()} is the assertion that item-derived idempotency keys
 * make the repeat a no-op.</li>
 * </ol>
 * {@code CONTINUE_AND_RETRY_ITEM_LATER} needs no harness support: a failed item simply returns on the
 * next {@code whenBatchRuns()} because the todo list still projects it — unless {@code onFailure}
 * recorded an event that defers or drops it, which the next round's projection applies, exactly as in
 * production.
 *
 * <h2>What not to do</h2>
 * <b>Never register the automation or its todo list on the builder</b> (via {@link #configure}).
 * The harness owns both instances and drives them itself; registering them would start a real
 * processor whose thread races these synchronous rounds, and every assertion would turn
 * non-deterministic. {@code configure} is a no-op here for exactly that reason.
 *
 * <h2>What this deliberately does not cover</h2>
 * The processor around the loop: leader election, the catch-up guard against a live projector,
 * backoff pacing, {@code AutomationStatus} and the bounded-context events. Those are framework
 * behaviour, pinned by the framework's own integration tests — a user testing their automation is
 * testing {@code handle}, {@code onFailure}, the todo list's projection and the idempotency keys,
 * which is what this base makes cheap.
 *
 * @param <TODO_ITEM_TYPE> the automation's todo item type
 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
 * @param <INBOUND_EVENT_TYPE> the bounded context's inbound event type
 * @param <OUTBOUND_EVENT_TYPE> the bounded context's outbound event type
 */
public abstract class AutomationTest<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

	private Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automationUnderTest;
	private Projector<DOMAIN_EVENT_TYPE> todoListProjector;
	private boolean automationStopped;

	/**
	 * The automation under test, owning its todo list. Called once per test method; return a fresh
	 * pair, never a shared one — the harness projects the todo list itself, and state left over from
	 * another test would be indistinguishable from projected state.
	 */
	public abstract Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation ( );

	/**
	 * Nothing to register: the harness owns the automation and its todo list (see the class javadoc
	 * for why registering them would break the tests). Override only to register something else the
	 * automation's commands need.
	 */
	@Override
	public void configure ( BoundedContextBuilder<?> boundedContextBuilder ) {
	}

	public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
		return new TestDefinition().given(events);
	}

	/**
	 * Called lazily from the first fluent call rather than from {@code setUp()}, so a subclass'
	 * {@code automation()} may build on fields its own {@code @BeforeEach} assigns.
	 */
	private Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automationUnderTest ( ) {
		if ( automationUnderTest == null ) {
			automationUnderTest = automation();
			todoListProjector = Projector.from(domainStream()).towards(automationUnderTest.getTodoList()).build();
		}
		return automationUnderTest;
	}

	/**
	 * Per-item, through {@code AutomationBatch.correlatedContexts} — the same derivation production
	 * runs, so an item implementing {@code CorrelatedTodoItem} has its handling stamped with its
	 * flow's correlation id in a test exactly as it would be at runtime.
	 */
	private Function<TODO_ITEM_TYPE,AutomationContext<DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automationContexts ( ) {
		return AutomationBatch.correlatedContexts(
				tracing -> new AutomationContextImpl<>(kernel(), tracing),
				Tracing.init(InstanceFactory.determine("unittests")));
	}

	public class TestDefinition {

		public TestDefinition given ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e -> kernel().event(e));
			return this;
		}

		public TestDefinition events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			return given(events);
		}

		public TestDefinition event ( DOMAIN_EVENT_TYPE event, Tags tags ) {
			kernel().event(event, tags);
			return this;
		}

		/**
		 * Projects everything appended so far into the todo list and asserts it offers exactly
		 * {@code expected}, in that order. The list is read through {@code streamItems} with room for
		 * one item more than expected, so a surplus item fails the count rather than hiding.
		 */
		public TestDefinition expectTodoItems ( @SuppressWarnings("unchecked") TODO_ITEM_TYPE... expected ) {
			automationUnderTest();
			todoListProjector.run();
			List<TODO_ITEM_TYPE> actual = automationUnderTest().getTodoList().streamItems(Limit.to(expected.length + 1L)).toList();
			assertEquals(expected.length, actual.size(), "number of todo items not as expected, was " + actual);
			for ( int i = 0; i < expected.length; i++ ) {
				assertCompareObjects(expected[i], actual.get(i), "todo item #%d".formatted(i));
			}
			return this;
		}

		/** Projects everything appended so far into the todo list and asserts it offers nothing. */
		public TestDefinition expectNoTodoItems ( ) {
			automationUnderTest();
			todoListProjector.run();
			List<TODO_ITEM_TYPE> actual = automationUnderTest().getTodoList().streamItems(Limit.to(1)).toList();
			assertEquals(0, actual.size(), "no todo items expected, was " + actual);
			return this;
		}

		/**
		 * Puts a stopped automation back to work, the counterpart of
		 * {@code AutomationAdminCapability.restartAutomation}. Only valid after a batch that asked to
		 * stop; remember the item that stopped it is still at the head of the todo list, so a restart
		 * without fixing the cause stops again.
		 */
		public TestDefinition restartAutomation ( ) {
			if ( !automationStopped ) {
				fail("the automation is not stopped - restartAutomation() is only meaningful after a batch ended in STOP_AUTOMATION");
			}
			automationStopped = false;
			return this;
		}

		/**
		 * Catches the todo list up with everything appended so far, then runs exactly one batch — the
		 * ordinary production round, synchronously.
		 */
		public BatchResult whenBatchRuns ( ) {
			return runOneBatch(true);
		}

		/**
		 * Runs one batch <em>without</em> catching the todo list up first, so it re-offers the items
		 * whose events have not reached it — at-least-once delivery, made reproducible. This is what a
		 * crash between the append and the bookmark (or a leader-failover overlap) does in production;
		 * follow it with {@link BatchResult#noEvents()} to prove the item-derived idempotency keys make
		 * the repeat a no-op.
		 */
		public BatchResult whenItemsAreRedelivered ( ) {
			return runOneBatch(false);
		}

		private BatchResult runOneBatch ( boolean catchUpTodoListFirst ) {
			Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation = automationUnderTest();
			if ( automationStopped ) {
				fail("the automation has stopped itself - in production it will not run again until it is restarted; call restartAutomation() to put it back to work");
			}
			if ( catchUpTodoListFirst ) {
				todoListProjector.run();
			}

			EventReference domainBookmark = lastReferenceOf(domainStream().query(EventQuery.matchAll()).map(Event::reference).toList());
			EventReference outboundBookmark = lastReferenceOf(outboundStream().query(EventQuery.matchAll()).map(Event::reference).toList());

			AutomationBatch.Outcome outcome = AutomationBatch.handleBatch(
					automation, automationContexts(), AutomationBatch.batchSizeOf(automation),
					automation.getClass().getSimpleName(), () -> false, null);

			if ( outcome.stopAutomation() ) {
				automationStopped = true;
			}

			List<Event<DOMAIN_EVENT_TYPE>> newDomainEvents = domainStream().query(EventQuery.matchAll(), domainBookmark, Limit.none()).toList();
			List<Event<OUTBOUND_EVENT_TYPE>> newOutboundEvents = outboundStream().query(EventQuery.matchAll(), outboundBookmark, Limit.none()).toList();
			return new BatchResult(this, outcome, newDomainEvents, newOutboundEvents);
		}

		private EventReference lastReferenceOf ( List<EventReference> references ) {
			return references.isEmpty() ? null : references.get(references.size() - 1);
		}
	}

	/**
	 * What one batch did: the events it durably appended (to the domain and outbound streams), the
	 * item counts, and whether it asked to stop. Chainable; {@link #and()} continues with the next
	 * round.
	 */
	public class BatchResult {

		private final TestDefinition definition;
		private final AutomationBatch.Outcome outcome;
		private final List<Event<DOMAIN_EVENT_TYPE>> newDomainEvents;
		private final List<Event<OUTBOUND_EVENT_TYPE>> newOutboundEvents;

		private BatchResult ( TestDefinition definition, AutomationBatch.Outcome outcome, List<Event<DOMAIN_EVENT_TYPE>> newDomainEvents, List<Event<OUTBOUND_EVENT_TYPE>> newOutboundEvents ) {
			this.definition = definition;
			this.outcome = outcome;
			this.newDomainEvents = newDomainEvents;
			this.newOutboundEvents = newOutboundEvents;
		}

		/** Asserts this batch appended exactly {@code expected} to the domain stream, in that order. */
		public BatchResult events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... expected ) {
			assertEquals(expected.length, newDomainEvents.size(), "number of domain events appended by this batch not as expected, was " + newDomainEvents);
			for ( int i = 0; i < expected.length; i++ ) {
				assertCompareObjects(expected[i], newDomainEvents.get(i).data(), "domain event #%d".formatted(i));
			}
			return this;
		}

		/**
		 * Asserts this batch appended exactly one domain event, equal to {@code expected}, carrying at
		 * least all of {@code expectedTags} — extra tags (e.g. tracing) are allowed, as in
		 * {@code CommandTest}.
		 */
		public BatchResult event ( DOMAIN_EVENT_TYPE expected, Tags expectedTags ) {
			events(expected);
			Event<DOMAIN_EVENT_TYPE> actual = newDomainEvents.get(0);
			assertTrue(actual.tags().containsAll(expectedTags),
					"event tags %s do not contain all expected tags %s".formatted(actual.tags().toStrings(), expectedTags.toStrings()));
			return this;
		}

		/**
		 * Asserts this batch appended nothing to the domain stream — after
		 * {@link TestDefinition#whenItemsAreRedelivered()}, the proof that the idempotency keys made
		 * the repeat a no-op.
		 */
		public BatchResult noEvents ( ) {
			assertEquals(0, newDomainEvents.size(), "no domain events expected from this batch, was " + newDomainEvents);
			return this;
		}

		/** Asserts this batch appended exactly {@code expected} to the outbound stream, in that order. */
		public BatchResult outboundEvents ( @SuppressWarnings("unchecked") OUTBOUND_EVENT_TYPE... expected ) {
			assertEquals(expected.length, newOutboundEvents.size(), "number of outbound events appended by this batch not as expected, was " + newOutboundEvents);
			for ( int i = 0; i < expected.length; i++ ) {
				assertCompareObjects(expected[i], newOutboundEvents.get(i).data(), "outbound event #%d".formatted(i));
			}
			return this;
		}

		/** Asserts this batch published nothing — the outbound half of a dedup proof. */
		public BatchResult noOutboundEvents ( ) {
			assertEquals(0, newOutboundEvents.size(), "no outbound events expected from this batch, was " + newOutboundEvents);
			return this;
		}

		/** Asserts how many items {@code handle} completed in this batch. */
		public BatchResult itemsHandled ( long expected ) {
			assertEquals(expected, outcome.handled(), "number of items handled not as expected");
			return this;
		}

		/** Asserts how many items failed in this batch (each routed through {@code onFailure}). */
		public BatchResult itemsFailed ( long expected ) {
			assertEquals(expected, outcome.failed(), "number of items failed not as expected");
			return this;
		}

		/**
		 * Asserts this batch ended in {@link AutomationFailureAction#STOP_AUTOMATION}. Until
		 * {@link TestDefinition#restartAutomation()} is called, further batches refuse to run — as in
		 * production, where a stopped automation waits for an operator.
		 */
		public BatchResult automationStopped ( ) {
			assertTrue(outcome.stopAutomation(), "the batch did not ask to stop the automation");
			return this;
		}

		/** Asserts this batch, whatever failed in it, did not stop the automation. */
		public BatchResult automationStillRunning ( ) {
			assertFalse(outcome.stopAutomation(), "the batch asked to stop the automation");
			return this;
		}

		/** Continues with the next round: more given events, todo list assertions, another batch. */
		public TestDefinition and ( ) {
			return definition;
		}
	}

}
