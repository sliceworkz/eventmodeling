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
package org.sliceworkz.eventmodeling.module.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.PortCallFailed;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.PortCallRejected;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.PortCalled;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.PortCallsSummarized;
import org.sliceworkz.eventmodeling.boundedcontext.StreamAppendingBoundedContextListener;
import org.sliceworkz.eventmodeling.commands.BusinessException;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.ports.Consumption;
import org.sliceworkz.eventmodeling.ports.PortCall;
import org.sliceworkz.eventmodeling.ports.PortMonitoring;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * What an adapter reports it consumed through {@link PortCall#current()} reaches the call's port event, its
 * summary window and its observation, whatever the call's outcome; and outside a monitored call it reaches
 * nothing. Framework behaviour, so plain {@code @Test}s against the in-memory store.
 */
public class PortConsumptionTest extends AbstractMockDomainTest {

	/** A paid service: what each method costs is the adapter's to report. */
	public interface PaidPort {
		String ask ( String question );
		String refuse ( String question );
		String breakDown ( String question );
		String retryOnAnotherModel ( String question );
	}

	static final Consumption ASKED = Consumption.of("anthropic", "big-model").quantity("input_tokens", 100).quantity("output_tokens", 40).reference("msg_1");

	/** Reports from the adapter, as a real one does after the provider answered. */
	public static class MeteringAdapter implements PaidPort {
		final AtomicReference<PortCall> kept = new AtomicReference<>();

		@Override
		public String ask ( String question ) {
			kept.set(PortCall.current());
			PortCall.current().consumed(ASKED);
			return "answer to " + question;
		}

		@Override
		public String refuse ( String question ) {
			// billed although refused: reported before the throw
			PortCall.current().consumed(Consumption.of("anthropic", "big-model").quantity("input_tokens", 80).quantity("output_tokens", 3));
			throw new BusinessException("declined: " + question);
		}

		@Override
		public String breakDown ( String question ) {
			PortCall.current().consumed(Consumption.of("anthropic", "big-model").quantity("input_tokens", 50));
			throw new IllegalStateException("broke on " + question);
		}

		@Override
		public String retryOnAnotherModel ( String question ) {
			PortCall call = PortCall.current();
			call.consumed(Consumption.of("anthropic", "big-model").quantity("input_tokens", 100).quantity("output_tokens", 0));
			// the second attempt runs on another thread, and reports through the handle it was given
			CompletableFuture.runAsync(( ) -> call.consumed(Consumption.of("anthropic", "big-model").served("small-model")
				.quantity("input_tokens", 100).quantity("output_tokens", 20))).join();
			return "answered by the fallback";
		}
	}

	private final List<EphemeralEvent<BoundedContextEvent>> observed = Collections.synchronizedList(new ArrayList<>());
	private MeteringAdapter adapter;

	@BeforeEach
	void fresh ( ) {
		adapter = new MeteringAdapter();
		observed.clear();
	}

	private BoundedContextBuilder<Mock> builder ( ) {
		return BoundedContext.newBuilder(Mock.class).name("UnitTestBoundedContext").eventStorage(eventStorage())
			.instance(InstanceFactory.determine("unittests")).listener(observed::add);
	}

	private PaidPort port ( PortMonitoring monitoring ) {
		Mock domain = buildBoundedContext(builder().adapter(adapter).monitored(monitoring).forPort(PaidPort.class));
		observed.clear();
		return domain.port(PaidPort.class);
	}

	private <T extends BoundedContextEvent> List<T> all ( Class<T> type ) {
		synchronized ( observed ) {
			return observed.stream().map(EphemeralEvent::data).filter(type::isInstance).map(type::cast).toList();
		}
	}

	@Test
	void whatAReturningCallConsumedIsOnItsPortCalledEvent ( ) {
		port(PortMonitoring.perCall()).ask("life");

		PortCalled called = all(PortCalled.class).getFirst();
		assertEquals(List.of(ASKED), called.consumption());
		assertEquals("big-model", called.consumption().getFirst().billedProduct());
	}

	@Test
	void aRejectedCallCarriesWhatItCost ( ) {
		PaidPort port = port(PortMonitoring.perCall());
		assertThrows(BusinessException.class, ( ) -> port.refuse("q"));

		PortCallRejected rejected = all(PortCallRejected.class).getFirst();
		assertEquals(80, rejected.consumption().getFirst().amount("input_tokens"));
		assertEquals(3, rejected.consumption().getFirst().amount("output_tokens"));
	}

	@Test
	void aFailedCallCarriesWhatItCost ( ) {
		PaidPort port = port(PortMonitoring.perCall());
		assertThrows(IllegalStateException.class, ( ) -> port.breakDown("q"));

		assertEquals(50, all(PortCallFailed.class).getFirst().consumption().getFirst().amount("input_tokens"));
	}

	@Test
	void everyReportOfACallIsKeptAlsoFromAnotherThread ( ) {
		port(PortMonitoring.perCall()).retryOnAnotherModel("q");

		List<Consumption> consumption = all(PortCalled.class).getFirst().consumption();
		assertEquals(2, consumption.size());
		assertEquals("big-model", consumption.get(0).billedProduct());
		assertEquals("small-model", consumption.get(1).billedProduct(), "the model that served the fallback is the one billed");
		assertEquals("big-model", consumption.get(1).product(), "and the one asked for stays known");
	}

	@Test
	void aSummarizedPortSumsWhatItsCallsConsumedPerProviderAndBilledProduct ( ) {
		Mock domain = buildBoundedContext(builder().adapter(adapter).monitored(PortMonitoring.summarized(Duration.ofHours(1))).forPort(PaidPort.class));
		PaidPort port = domain.port(PaidPort.class);
		port.ask("a");
		port.ask("b");
		port.retryOnAnotherModel("c");
		assertTrue(all(PortCalled.class).isEmpty());

		domain.stop();

		PortCallsSummarized asks = all(PortCallsSummarized.class).stream().filter(s -> s.method().equals("ask")).findFirst().orElseThrow();
		assertEquals(1, asks.consumption().size());
		Consumption summed = asks.consumption().getFirst();
		assertEquals(200, summed.amount("input_tokens"));
		assertEquals(80, summed.amount("output_tokens"));
		assertEquals(null, summed.reference(), "one call's reference does not add up");

		PortCallsSummarized retries = all(PortCallsSummarized.class).stream().filter(s -> s.method().equals("retryOnAnotherModel")).findFirst().orElseThrow();
		assertEquals(List.of("big-model", "small-model"), retries.consumption().stream().map(Consumption::billedProduct).toList(),
			"tokens of two models are never added together");
	}

	@Test
	void theObserverIsToldOnTheCallersThreadBeforeTheCallEnds ( ) {
		RecordingBoundedContextObserver recorder = new RecordingBoundedContextObserver();
		Mock domain = buildBoundedContext(builder().observer(recorder).adapter(adapter).monitored().forPort(PaidPort.class));
		PaidPort port = domain.port(PaidPort.class);

		port.retryOnAnotherModel("q");
		assertThrows(BusinessException.class, ( ) -> port.refuse("q"));

		List<RecordingBoundedContextObserver.Recording> calls = recorder.recordings(Observation.PortCall.class);
		assertEquals(2, calls.get(0).consumption().size(), "the report from the other thread too");
		assertEquals(80, calls.get(1).consumption().getFirst().amount("input_tokens"));
		assertTrue(recorder.violations().isEmpty(), recorder.violations().toString());
	}

	@Test
	void outsideAMonitoredCallNothingIsRecorded ( ) {
		Mock domain = buildBoundedContext(builder().adapter(adapter).forPort(PaidPort.class));
		PaidPort unmonitored = domain.port(PaidPort.class);
		assertSame(adapter, unmonitored);
		observed.clear();

		unmonitored.ask("q");
		assertFalse(adapter.kept.get().monitored());
		assertTrue(all(PortCalled.class).isEmpty());
	}

	@Test
	void aReportAfterTheCallEndedIsDroppedAndReachesNoLaterCall ( ) {
		PaidPort port = port(PortMonitoring.perCall());
		port.ask("first");
		PortCall ended = adapter.kept.get();
		ended.consumed(Consumption.of("anthropic", "big-model").quantity("input_tokens", 999));
		port.ask("second");

		List<PortCalled> calls = all(PortCalled.class);
		assertEquals(List.of(List.of(ASKED), List.of(ASKED)), calls.stream().map(PortCalled::consumption).toList());
	}

	/** The dashboard reads these back in another process: the consumption must survive the store. */
	@Test
	void consumptionSurvivesTheRoundTripThroughAMonitoringStream ( ) {
		EventStream<BoundedContextEvent> monitoring = EventStore.on(eventStorage()).build()
			.getEventStream(EventStreamId.forContext("monitoring").withPurpose("boundedcontext"), BoundedContextEvent.class);
		StreamAppendingBoundedContextListener persisting = new StreamAppendingBoundedContextListener(monitoring);
		Mock domain = buildBoundedContext(builder().listener(event -> { observed.add(event); persisting.on(event); })
			.adapter(adapter).monitored(PortMonitoring.perCall().summarizing(Duration.ofHours(1), "retryOnAnotherModel")).forPort(PaidPort.class));
		PaidPort port = domain.port(PaidPort.class);
		port.ask("q");
		assertThrows(BusinessException.class, ( ) -> port.refuse("q"));
		assertThrows(IllegalStateException.class, ( ) -> port.breakDown("q"));
		port.retryOnAnotherModel("q");
		domain.stop();

		List<BoundedContextEvent> stored = monitoring.query(EventFilter.matchAll()).stream().map(Event::data).toList();
		for ( Class<? extends BoundedContextEvent> type : List.of(PortCalled.class, PortCallRejected.class, PortCallFailed.class, PortCallsSummarized.class) ) {
			List<? extends BoundedContextEvent> written = all(type);
			assertEquals(1, written.size(), type.getSimpleName());
			assertEquals(written, stored.stream().filter(type::isInstance).toList(), type.getSimpleName() + " reads back as it was written");
		}
	}
}
