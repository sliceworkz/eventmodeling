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
package org.sliceworkz.eventmodeling.module.inbound;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A translator's processor now reports its lifecycle the way a read model's projector does —
 * {@code TranslatorStarted}/{@code TranslatorFailed}/{@code TranslatorStopped}. It used to pass no
 * listener at all: a translator that had stopped reading the inbound stream was two log lines, with
 * no bounded-context event and nothing to fold, and a transient failure retired it for good.
 */
public class TranslatorLifecycleTest extends AbstractMockDomainTest {

	private static final String CONTEXT_NAME = "TranslatorLifecycleBoundedContext";

	private final List<BoundedContextEvent> received = Collections.synchronizedList(new ArrayList<>());

	private String previousInitial;
	private String previousMax;

	@BeforeEach
	void shortenRetryPacing ( ) {
		previousInitial = System.setProperty("sliceworkz.eventmodeling.projector.retry.initial.ms", "50");
		previousMax = System.setProperty("sliceworkz.eventmodeling.projector.retry.max.ms", "200");
	}

	@AfterEach
	void restoreRetryPacing ( ) {
		restore("sliceworkz.eventmodeling.projector.retry.initial.ms", previousInitial);
		restore("sliceworkz.eventmodeling.projector.retry.max.ms", previousMax);
	}

	private static void restore ( String property, String previous ) {
		if ( previous == null ) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, previous);
		}
	}

	/** Announced at startup, so a translator with nothing to translate is not the same silence as one that is not deployed. */
	@Test
	void aTranslatorAnnouncesItselfAtStartup ( ) {
		FlakyTranslator translator = new FlakyTranslator();

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.translator(translator);
		buildBoundedContext(builder);

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertNotNull(started("FlakyTranslator"), "a translator must announce its processor at startup: " + received));
		assertEquals(CONTEXT_NAME, started("FlakyTranslator").boundedContext());
	}

	/**
	 * The recovery story, at the translator: a transiently failing translation is retried with
	 * backoff, reported per fruitless round, and the domain event lands exactly once when the cause
	 * clears — the inbound event was never skipped, and the translator never retired.
	 */
	@Test
	void aTransientlyFailingTranslationRetriesAndTheDomainEventLandsOnce ( ) {
		FlakyTranslator translator = new FlakyTranslator();
		translator.failing.set(true);

		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.translator(translator);
		Mock domain = buildBoundedContext(builder);

		domain.incoming(new SomeInboundEvent("survives-the-outage"));

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertTrue(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.TranslatorFailed f
								&& f.translator().equals("FlakyTranslator")),
						"every fruitless retry round must be reported: " + received));

		translator.failing.set(false); // the outage ends; nothing is restarted

		await().atMost(Duration.ofSeconds(15)).untilAsserted(
				() -> assertEquals(List.of(new FirstDomainEvent("survives-the-outage")), translatedDomainEvents(),
						"the domain event must land exactly once, once the cause clears"));

		assertFalse(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.TranslatorStopped),
				"a failure worth retrying must not retire the translator: " + received);
	}

	/** The shared shutdown asymmetry: going down with the context is not a stop worth reporting per translator. */
	@Test
	void aTranslatorGoingDownWithItsContextReportsNothing ( ) {
		BoundedContextBuilder<Mock> builder = observedBuilder();
		builder.translator(new FlakyTranslator());
		buildBoundedContext(builder);

		received.clear();
		releaseBoundedContext();

		assertTrue(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.BoundedContextStopping),
				"the context must report its own shutdown: " + received);
		assertFalse(received.stream().anyMatch(e -> e instanceof BoundedContextEvent.TranslatorStopped),
				"shutdown is reported by BoundedContextStopping, not per translator: " + received);
	}

	// ---------------------------------------------------------------------------------------------

	private BoundedContextBuilder<Mock> observedBuilder ( ) {
		return BoundedContext.newBuilder(Mock.class)
				.name(CONTEXT_NAME)
				.eventStorage(eventStorage())
				.instance(InstanceFactory.determine("unittests"))
				.listener(event -> received.add(event.data()));
	}

	private BoundedContextEvent.TranslatorStarted started ( String translator ) {
		synchronized ( received ) {
			return received.stream()
					.filter(BoundedContextEvent.TranslatorStarted.class::isInstance)
					.map(BoundedContextEvent.TranslatorStarted.class::cast)
					.filter(e -> translator.equals(e.translator()))
					.findFirst().orElse(null);
		}
	}

	private List<MockDomainEvent> translatedDomainEvents ( ) {
		EventStream<MockDomainEvent> domainStream = EventStoreFactory.get().eventStore(eventStorage())
				.getEventStream(EventStreamId.forContext(CONTEXT_NAME).withPurpose("domain"), MockDomainEvent.class);
		return domainStream.query(EventQuery.matchAll()).map(Event::data).toList();
	}

	/** A translator that fails while the flag is set — what its target being down looks like from the processor. */
	static class FlakyTranslator implements Translator<MockInboundEvent,MockDomainEvent> {

		final AtomicBoolean failing = new AtomicBoolean();

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(SomeInboundEvent.class), Tags.none());
		}

		@Override
		public void translate ( MockInboundEvent event, TranslatorContext<MockInboundEvent,MockDomainEvent> context ) {
			if ( failing.get() ) {
				throw new IllegalStateException("this translator's dependency is down");
			}
			switch ( event ) {
				case SomeInboundEvent e -> context.event(new FirstDomainEvent(e.someValue()), "translated-" + e.someValue());
			}
		}
	}

}
