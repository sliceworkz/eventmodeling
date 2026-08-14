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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.Untyped;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.EventStoreBackend;
import org.sliceworkz.eventstore.testing.ForEachBackend;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for the published test bases ({@link CommandTest}, {@link AggregateTest},
 * {@link LiveModelTest}): builds a bounded context over an event storage and releases both around
 * every test method.
 * <p>
 * <b>Which storage that is depends on how the test methods are annotated</b>, and it is the same
 * choice the framework's own suite makes:
 * <ul>
 *   <li>{@link org.junit.jupiter.api.Test @Test} — one run against an in-memory store. Nothing to
 *       configure, and what every test written before this existed keeps doing.</li>
 *   <li>{@link ForEachBackend @ForEachBackend} — one run per {@link EventStoreBackend} registered
 *       in {@code META-INF/services/org.sliceworkz.eventstore.testing.EventStoreBackend} on the
 *       test classpath, each reported under the backend that produced it
 *       ({@code openingAnAccount [postgres:18]}). Use it where the scenario is worth proving
 *       against the storage the application will actually run on.</li>
 * </ul>
 * <pre>{@code
 * class OpenAccountCommandTest extends CommandTest<BankingEvent, Void, Void> {
 *
 *     @ForEachBackend                      // in-memory, in-memory-fs, PostgreSQL, ...
 *     void openingAnAccount ( ) {
 *         given().when(new OpenAccountCommand("123")).then().event(new AccountOpened("123"));
 *     }
 * }
 * }</pre>
 * Registering a backend is a line in that service file plus the storage on the test classpath —
 * {@code InMemoryBackend} needs nothing further, the PostgreSQL ones need
 * {@code sliceworkz-eventstore-infra-postgres} and a Docker daemon for Testcontainers. Narrow a
 * local run with {@code -Deventstore.testing.backends=inmem}. See this module's README.
 * <p>
 * The storage lifecycle itself comes from {@link AbstractEventStoreTest}: a fresh, empty store
 * before each test method, released after it. Subclasses reach it through {@link #eventStorage()}
 * and must not build one themselves.
 *
 * @param <DOMAIN_EVENT_TYPE>   the bounded context's domain event type
 * @param <INBOUND_EVENT_TYPE>  the bounded context's inbound event type
 * @param <OUTBOUND_EVENT_TYPE> the bounded context's outbound event type
 */
public abstract class AbstractBoundedContextTest<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> extends AbstractEventStoreTest {

	private static final String DOMAIN_NAME = "unitTests";
	private static final String DOMAIN = "domain";
	// the purposes the bounded context opens its three streams under. Duplicated from the builder
	// implementation's private constants; the *TestRunsOnEveryBackendTest classes pin the literals, so
	// drift fails a build rather than silently reading empty streams
	private static final String INBOUND = "inbound";
	private static final String OUTBOUND = "outbound";

	private static final String IGNORE_TEXT = "<<<IGNORE>>>";
	private static final String IGNORE_ID_TEXT = "<<<IGNORE_ID>>>";
	private static final Calendar IGNORE_DATE_CALENDAR = new GregorianCalendar(); static {IGNORE_DATE_CALENDAR.set(666, 6, 6, 6, 6, 6);};
	private static final Date IGNORE_DATE = IGNORE_DATE_CALENDAR.getTime();

	private Instance INSTANCE = InstanceFactory.determine("unittests");

	private BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> boundedContext;

	@SuppressWarnings("unchecked")
	@Override
	@BeforeEach
	public void setUp ( ) {
		// creates the storage -- the backend's for a @ForEachBackend invocation, the in-memory one
		// of createEventStorage() otherwise -- plus the store this test queries through eventStore()
		super.setUp();

		BoundedContextBuilder<?> builder = BoundedContext.newBuilder(Untyped.class)
				.eventTypes(domainEventType(), inboundEventType(), outboundEventType());

		builder
				.name(DOMAIN_NAME)
				.instance(INSTANCE)
				.eventStorage(eventStorage());

		// let subclasses do any needed configuration
		configure(builder);

		this.boundedContext = (BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>) builder.build();
	}

	/**
	 * Releases everything {@link #setUp()} created, in ownership order: the bounded context (which
	 * closes the store it built for itself), then — through {@link AbstractEventStoreTest} — the
	 * store handed to the test by {@link #eventStore()} and the storage that backs both.
	 * <p>
	 * Without this every test method would leave a bounded context and two stores behind, each with
	 * its own processor and notification threads, for the rest of the JVM's life.
	 */
	@Override
	@AfterEach
	public void tearDown ( ) {
		if ( boundedContext != null ) {
			boundedContext.terminate();
			boundedContext = null;
		}
		super.tearDown();
	}

	/**
	 * The in-memory storage a plain {@code @Test} runs against.
	 * <p>
	 * {@link AbstractEventStoreTest} would otherwise demand a bound backend and fail the test with
	 * an explanation; overriding it here makes the in-memory store the default — so a test written
	 * before the matrix existed keeps running exactly as it did — and leaves {@link ForEachBackend}
	 * as the deliberate opt-in to every registered storage.
	 *
	 * @return a fresh, empty storage
	 */
	@Override
	protected EventStorage createEventStorage ( ) {
		return hasBoundBackend() ? super.createEventStorage() : InMemoryEventStorage.newBuilder().build();
	}

	/**
	 * Releases the storage {@link #createEventStorage()} produced: through the backend that built
	 * it, or by closing it ourselves when we built the in-memory one above. The base class releases
	 * only what a bound backend gave it, so without this branch the storage of every plain
	 * {@code @Test} would stay open.
	 *
	 * @param storage the storage to release
	 */
	@Override
	protected void destroyEventStorage ( EventStorage storage ) {
		if ( hasBoundBackend() ) {
			super.destroyEventStorage(storage);
		} else {
			storage.close();
		}
	}

	/**
	 * Whether a {@link ForEachBackend} invocation bound a backend to this test.
	 * <p>
	 * {@link AbstractEventStoreTest#backend()} throws rather than returning {@code null} when none
	 * is bound, which is the right contract for a test that requires one but leaves no way to ask.
	 */
	private boolean hasBoundBackend ( ) {
		try {
			backend();
			return true;
		} catch (IllegalStateException noBackendBound) {
			return false;
		}
	}

	public abstract Class<DOMAIN_EVENT_TYPE> domainEventType ( );

	public abstract Class<INBOUND_EVENT_TYPE> inboundEventType ( );

	public abstract Class<OUTBOUND_EVENT_TYPE> outboundEventType ( );

	public abstract void configure ( BoundedContextBuilder<?> builder );

	public BoundedContext<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> kernel ( ) {
		return boundedContext;
	}

	public EventStreamId eventStreamId ( ) {
		return EventStreamId.forContext(DOMAIN_NAME).withPurpose(DOMAIN);
	}

	/** The stream the bounded context under test receives inbound events on. */
	public EventStreamId inboundEventStreamId ( ) {
		return EventStreamId.forContext(DOMAIN_NAME).withPurpose(INBOUND);
	}

	/** The stream the bounded context under test publishes outbound events on. */
	public EventStreamId outboundEventStreamId ( ) {
		return EventStreamId.forContext(DOMAIN_NAME).withPurpose(OUTBOUND);
	}

	/** The domain stream of the bounded context under test, over {@link #eventStore()}. */
	protected EventStream<DOMAIN_EVENT_TYPE> domainStream ( ) {
		return eventStore().getEventStream(eventStreamId(), domainEventType());
	}

	/** The inbound stream of the bounded context under test, over {@link #eventStore()}. */
	protected EventStream<INBOUND_EVENT_TYPE> inboundStream ( ) {
		return eventStore().getEventStream(inboundEventStreamId(), inboundEventType());
	}

	/** The outbound stream of the bounded context under test, over {@link #eventStore()}. */
	protected EventStream<OUTBOUND_EVENT_TYPE> outboundStream ( ) {
		return eventStore().getEventStream(outboundEventStreamId(), outboundEventType());
	}

	/**
	 * The store over {@link #eventStorage()}, for a test asserting on what a command actually wrote.
	 * <p>
	 * Widened from {@link AbstractEventStoreTest}'s {@code protected}: it has been part of this
	 * class' published surface since before the backend matrix, and subclasses outside this package
	 * call it.
	 *
	 * @return the event store, valid for the duration of one test
	 */
	@Override
	public EventStore eventStore ( ) {
		return super.eventStore();
	}

	public void assertCompareJsonString ( Object expected, Object actual, String objectDescription ) {
		// Jackson 3.x: JsonMapper is immutable and configured via its builder.
		ObjectWriter mapper = JsonMapper.builder()
			.changeDefaultVisibility(vc -> vc.withVisibility(PropertyAccessor.FIELD, Visibility.ANY))
			.build()
			.writerWithDefaultPrettyPrinter();

		assertEquals(expected.getClass(), actual.getClass(), "type for %s not as expected".formatted(objectDescription));

		try {
			String expectedAsPrettyPrintString = mapper.writeValueAsString(expected);
			String actualAsPrettyPrintString = mapper.writeValueAsString(actual);
//			System.err.println(expectedAsPrettyPrintString);
//			System.out.println(actualAsPrettyPrintString);

			assertEquals(expectedAsPrettyPrintString, actualAsPrettyPrintString);

		} catch (JacksonException e) {
			throw new RuntimeException(e);
		}
	}

	public void assertCompareObjects ( Object expected, Object actual, String objectDescription ) {
		// Jackson 3.x: JsonMapper is immutable and configured via its builder; modules
		// (incl. java.time) auto-register, so findAndRegisterModules() is gone.
		ObjectWriter mapper = JsonMapper.builder()
			.changeDefaultVisibility(vc -> vc.withVisibility(PropertyAccessor.FIELD, Visibility.ANY))
			.build()
			.writerWithDefaultPrettyPrinter();

		assertEquals(expected.getClass(), actual.getClass(), "type for %s not as expected".formatted(objectDescription));

		try {
			String expectedAsPrettyPrintString = mapper.writeValueAsString(expected);
			String actualAsPrettyPrintString = mapper.writeValueAsString(actual);
//			System.err.println(expectedAsPrettyPrintString);
//			System.out.println(actualAsPrettyPrintString);

			String[] expectedParts = expectedAsPrettyPrintString.split("\\n");
			String[] actualParts = actualAsPrettyPrintString.split("\\n");

			List<String> expectedToCompare = new ArrayList<>();
			List<String> actualToCompare = new ArrayList<>();

			int j = 0;
			for ( String expectedLine: expectedParts ) {
				if ( expectedLine.contains(IGNORE_ID_TEXT) ) {
					// the field must be present and its value must be a UUID -- see IGNORE_ID(); only
					// the concrete value is normalised out, so the field name is still compared below
					expectedToCompare.add(expectedLine);
					if ( actualParts.length > j ) {
						actualToCompare.add(withUuidValueVerified(expectedLine, actualParts[j], objectDescription));
					}
				} else if ( ! expectedLine.contains(IGNORE_TEXT) && ! expectedLine.contains(mapper.writeValueAsString(IGNORE_DATE))) {
					expectedToCompare.add(expectedLine);
					if ( actualParts.length > j ) {
						String actualLine = actualParts[j];
						actualToCompare.add(actualLine);
					}
				}
				j++;
			}

			if (! expectedToCompare.equals(actualToCompare)) {
				System.out.println("EXPECTED : " + expectedToCompare);
				System.out.println("ACTUAL   : " + actualToCompare);
			}
			assertEquals(expectedToCompare, actualToCompare);

		} catch (JacksonException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Verifies the actual line carries a UUID where the expected line carries the
	 * {@link #IGNORE_ID()} marker, and returns the actual line with that UUID replaced by the
	 * marker — so everything else about the line (field name, nesting, punctuation) is still
	 * compared against the expected one.
	 */
	private String withUuidValueVerified ( String expectedLine, String actualLine, String objectDescription ) {
		String value = quotedValueOf(actualLine);
		if ( value == null ) {
			fail("expected a UUID for field %s of %s, but the actual value is not a string: %s"
					.formatted(fieldNameOf(expectedLine), objectDescription, actualLine.strip()));
		}
		try {
			UUID.fromString(value);
		} catch ( IllegalArgumentException notAUuid ) {
			fail("value \"%s\" of field %s of %s is not a UUID"
					.formatted(value, fieldNameOf(expectedLine), objectDescription));
		}
		return actualLine.replace("\"" + value + "\"", "\"" + IGNORE_ID_TEXT + "\"");
	}

	/**
	 * The string value of a pretty-printed {@code "field" : "value"} line, or {@code null} when the
	 * line carries no quoted value (absent, {@code null}, a number, the start of a nested object).
	 */
	private static String quotedValueOf ( String jsonLine ) {
		int colon = jsonLine.indexOf(':');
		if ( colon < 0 ) {
			return null;
		}
		String raw = jsonLine.substring(colon + 1).strip();
		if ( raw.endsWith(",") ) {
			raw = raw.substring(0, raw.length() - 1).strip();
		}
		if ( raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"' ) {
			return null;
		}
		return raw.substring(1, raw.length() - 1);
	}

	private static String fieldNameOf ( String jsonLine ) {
		int colon = jsonLine.indexOf(':');
		String raw = ( colon < 0 ? jsonLine : jsonLine.substring(0, colon) ).strip();
		if ( raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"") ) {
			return raw.substring(1, raw.length() - 1);
		}
		return raw;
	}

	/**
	 * Marker for an id field whose concrete value cannot be predicted: the field must be present on
	 * the actual object and its value must parse as a {@link UUID} — only the value itself is not
	 * compared. For an id that is legitimately not a UUID, use {@link #IGNORE_TEXT()}, which skips
	 * the field entirely.
	 */
	public static String IGNORE_ID() {
		return IGNORE_ID_TEXT;
	}

	/**
	 * Marker for a field to leave out of the comparison entirely: neither its presence nor its
	 * value is checked.
	 */
	public static String IGNORE_TEXT() {
		return IGNORE_TEXT;
	}

	public static Date IGNORE_DATE() {
		return IGNORE_DATE;
	}

}
