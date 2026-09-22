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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateContext;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.automation.AutomationContext;
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.PublishingReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

/**
 * Every registration on the builder is wildcard-typed, so a component of another bounded context's
 * event types compiles. It would then never be handed an event it recognises — its {@code eventQuery()}
 * names types this context's stream does not hold — so it would stay empty for good, silently.
 * {@code build()} names it instead.
 * <p>
 * Related types are deliberately not refused: a supertype (the analytics read model over
 * {@code Object}) and a branch of the hierarchy are both legitimate, and a check that rejects a
 * legitimate registration is worse than none. Framework behaviour, not storage behaviour, so plain
 * {@code @Test}s.
 */
public class ForeignEventTypeRegistrationTest extends AbstractMockDomainTest {

	@Test
	void aLiveReadModelOfAnotherContextIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder().readmodel(ForeignReadModel.class).live().build());
		assertEquals("component registered over another bounded context's event types: "
				+ "readmodel ForeignReadModel (its domain event type is " + ForeignDomainEvent.class.getName()
				+ ", where this context's is " + MockDomainEvent.class.getName() + ")",
				e.getMessage());
	}

	@Test
	void anEventuallyConsistentReadModelOfAnotherContextIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder().readmodel(new ForeignPublishingReadModel()).eventuallyConsistent().build());
		assertTrue(e.getMessage().contains("readmodel ForeignPublishingReadModel (its domain event type is "
				+ ForeignDomainEvent.class.getName()), e.getMessage());
	}

	/** The type argument is read through the generic superclass it is bound on, not off the class alone. */
	@Test
	void theEventTypeIsResolvedThroughAGenericBaseClass ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.readmodel(new MockPublishingReadModel()).eventuallyConsistent();
		assertNotNull(buildBoundedContext(builder));
	}

	@Test
	void anAggregateOfAnotherContextIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder().aggregate(ForeignAggregate.class).done().build());
		assertTrue(e.getMessage().contains("aggregate ForeignAggregate (its domain event type is "
				+ ForeignDomainEvent.class.getName()), e.getMessage());
	}

	@Test
	void anAutomationOfAnotherContextIsRejectedOnBothItsEventTypes ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder().automation(new ForeignAutomation()).build());
		assertTrue(e.getMessage().contains("automation ForeignAutomation (its domain event type is "
				+ ForeignDomainEvent.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("automation ForeignAutomation (its outbound event type is "
				+ ForeignOutboundEvent.class.getName()), e.getMessage());
	}

	@Test
	void aTranslatorOfAnotherContextIsRejectedOnBothItsEventTypes ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder().translator(new ForeignTranslator()).build());
		assertTrue(e.getMessage().contains("translator ForeignTranslator (its inbound event type is "
				+ ForeignInboundEvent.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("translator ForeignTranslator (its domain event type is "
				+ ForeignDomainEvent.class.getName()), e.getMessage());
	}

	@Test
	void aDispatcherOfAnotherContextIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder().dispatcher(new ForeignDispatcher()).build());
		assertTrue(e.getMessage().contains("dispatcher ForeignDispatcher (its outbound event type is "
				+ ForeignOutboundEvent.class.getName()), e.getMessage());
	}

	@Test
	void everyOffendingComponentIsNamedAtOnce ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				baseBuilder()
					.readmodel(ForeignReadModel.class).live()
					.dispatcher(new ForeignDispatcher())
					.translator(new ForeignTranslator())
					.build());
		assertTrue(e.getMessage().contains("readmodel ForeignReadModel"), e.getMessage());
		assertTrue(e.getMessage().contains("dispatcher ForeignDispatcher"), e.getMessage());
		assertTrue(e.getMessage().contains("translator ForeignTranslator"), e.getMessage());
	}

	/**
	 * A read model over a supertype of this context's root projects whatever it is handed, which is
	 * what an analytics model spanning contexts does. Accepted.
	 */
	@Test
	void aReadModelOverASupertypeIsAccepted ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.readmodel(AnalyticsReadModel.class).live();
		assertNotNull(buildBoundedContext(builder));
	}

	/**
	 * A read model over a branch of the hierarchy is kept away from the other branches by its own
	 * {@code eventQuery()}, which nothing here can judge. Accepted.
	 */
	@Test
	void aReadModelOverABranchOfTheHierarchyIsAccepted ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.readmodel(BranchReadModel.class).live();
		assertNotNull(buildBoundedContext(builder));
	}

	/**
	 * A declaration fixing no event class at all is not evidence of the wrong one, so it passes rather
	 * than being refused on a resolution that failed. A generic read model registered as an instance is
	 * that shape: its class implements {@code ReadModel<E>}, and the argument the instance was created
	 * with is not there to be read.
	 */
	@Test
	void aReadModelWhoseDeclarationFixesNoEventTypeIsAccepted ( ) {
		BoundedContextBuilder<Mock> builder = baseBuilder();
		builder.readmodel(new GenericReadModel<MockDomainEvent>()).eventuallyConsistent();
		assertNotNull(buildBoundedContext(builder));
	}

	private BoundedContextBuilder<Mock> baseBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name("UnitTestBoundedContext")
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"));
	}

	// ── another context's event types ────────────────────────────────────────

	sealed interface ForeignDomainEvent {
		record SomethingHappened ( String id ) implements ForeignDomainEvent { }
	}

	sealed interface ForeignInboundEvent {
		record SomethingArrived ( String id ) implements ForeignInboundEvent { }
	}

	sealed interface ForeignOutboundEvent {
		record SomethingAnnounced ( String id ) implements ForeignOutboundEvent { }
	}

	public static class ForeignReadModel implements ReadModel<ForeignDomainEvent> {
		@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override public void when ( Event<ForeignDomainEvent> event ) { }
	}

	public static class ForeignPublishingReadModel extends PublishingReadModel<ForeignDomainEvent,Integer> {
		@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override protected Integer initialState ( ) { return 0; }
		@Override protected Integer apply ( Integer state, Event<ForeignDomainEvent> event ) { return state + 1; }
	}

	public static class MockPublishingReadModel extends PublishingReadModel<MockDomainEvent,Integer> {
		@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override protected Integer initialState ( ) { return 0; }
		@Override protected Integer apply ( Integer state, Event<MockDomainEvent> event ) { return state + 1; }
	}

	public static class ForeignAggregate implements Aggregate<ForeignDomainEvent> {
		@Override public void setContext ( AggregateContext<ForeignDomainEvent> aggregateContext ) { }
		@Override public void when ( Event<ForeignDomainEvent> event ) { }
	}

	public static class ForeignTranslator implements Translator<ForeignInboundEvent,ForeignDomainEvent> {
		@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override public void translate ( ForeignInboundEvent event, TranslatorContext<ForeignInboundEvent,ForeignDomainEvent> context ) { }
	}

	public static class ForeignDispatcher implements Dispatcher<ForeignOutboundEvent> {
		@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override public void when ( Event<ForeignOutboundEvent> event ) { }
	}

	public static class ForeignAutomation implements Automation<String,ForeignDomainEvent,ForeignOutboundEvent> {
		private final ForeignTodoList todoList = new ForeignTodoList();
		@Override public TodoListReadModel<ForeignDomainEvent,String> getTodoList ( ) { return todoList; }
		@Override public Optional<EventReference> handle ( String item, AutomationContext<ForeignDomainEvent,ForeignOutboundEvent> context ) { return Optional.empty(); }
	}

	public static class ForeignTodoList implements TodoListReadModel<ForeignDomainEvent,String> {
		@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override public void when ( Event<ForeignDomainEvent> event ) { }
		@Override public Stream<String> streamItems ( Limit limit ) { return Stream.empty(); }
		@Override public Optional<EventReference> lastEventReference ( ) { return Optional.empty(); }
	}

	// ── related types, which are not the mistake this catches ────────────────

	public static class AnalyticsReadModel implements ReadModel<Object> {
		@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override public void when ( Event<Object> event ) { }
	}

	public static class BranchReadModel implements ReadModel<MockDomainEvent.FirstDomainEvent> {
		@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override public void when ( Event<MockDomainEvent.FirstDomainEvent> event ) { }
	}

	public static class GenericReadModel<E> implements ReadModel<E> {
		@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override public void when ( Event<E> event ) { }
	}

}
