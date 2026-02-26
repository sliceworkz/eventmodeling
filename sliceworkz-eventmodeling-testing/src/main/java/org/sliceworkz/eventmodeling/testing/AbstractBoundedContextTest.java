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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.Untyped;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStreamId;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;

public abstract class AbstractBoundedContextTest<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	private static final String DOMAIN_NAME = "unitTests";
	private static final String DOMAIN = "domain";

	private static final String IGNORE_TEXT = "<<<IGNORE>>>";
	private static final Calendar IGNORE_DATE_CALENDAR = new GregorianCalendar(); static {IGNORE_DATE_CALENDAR.set(666, 6, 6, 6, 6, 6);};
	private static final Date IGNORE_DATE = IGNORE_DATE_CALENDAR.getTime();

	private Instance INSTANCE = InstanceFactory.determine("unittests");

	private BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> boundedContext;
	private EventStorage eventStorage;
	private EventStore eventStore;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp ( ) {
		this.eventStorage = InMemoryEventStorage.newBuilder().build();

		this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
		BoundedContextBuilder<?> builder = BoundedContext.newBuilder(Untyped.class)
				.eventTypes(domainEventType(), inboundEventType(), outboundEventType());

		builder
				.name(DOMAIN_NAME)
				.instance(INSTANCE)
				.eventStorage(eventStorage);

		// let subclasses do any needed configuration
		configure(builder);

		this.boundedContext = (BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>) builder.build();
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

	public EventStore eventStore ( ) {
		return eventStore;
	}

	public void assertCompareJsonString ( Object expected, Object actual, String objectDescription ) {
		ObjectWriter mapper = new JsonMapper()
			.setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
			.writerWithDefaultPrettyPrinter();

		assertEquals(expected.getClass(), actual.getClass(), "type for %s not as expected".formatted(objectDescription));

		try {
			String expectedAsPrettyPrintString = mapper.writeValueAsString(expected);
			String actualAsPrettyPrintString = mapper.writeValueAsString(actual);
//			System.err.println(expectedAsPrettyPrintString);
//			System.out.println(actualAsPrettyPrintString);

			assertEquals(expectedAsPrettyPrintString, actualAsPrettyPrintString);

		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	public void assertCompareObjects ( Object expected, Object actual, String objectDescription ) {
		ObjectWriter mapper = new JsonMapper()
			.findAndRegisterModules()
			.setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
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
				if ( ! expectedLine.contains(IGNORE_TEXT) && ! expectedLine.contains(mapper.writeValueAsString(IGNORE_DATE))) {
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

		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	// TODO maybe check UUID format for this one instead of just ignoring alltogether?
	public static String IGNORE_ID() {
		return IGNORE_TEXT;
	}

	public static String IGNORE_TEXT() {
		return IGNORE_TEXT;
	}

	public static Date IGNORE_DATE() {
		return IGNORE_DATE;
	}

}
