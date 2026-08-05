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
package org.sliceworkz.eventmodeling.module.aggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventstore.events.Tags;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A meter name carries one set of tag keys, everywhere. Prometheus enforces that at registration --
 * a second registration of the same name under a different key set throws there -- so the framework
 * has to be consistent about it whatever registry it is handed.
 */
public class AggregateMeterRegistrationTest extends AbstractMockDomainTest {

	private static final String LOAD_COUNT = "sliceworkz.eventmodeling.aggregate.load.count";

	/** Loading an aggregate registers the load counter once, tagged by channel, and increments it. */
	@Test
	void theAggregateLoadCounterIsRegisteredOnlyOnce ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		Mock domain = domainWithAggregate(registry);
		domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));

		List<Meter> loadCounters = registry.getMeters().stream()
				.filter(meter -> LOAD_COUNT.equals(meter.getId().getName()))
				.toList();

		assertEquals(1, loadCounters.size(), "the load counter should be registered exactly once: " + ids(loadCounters));
		assertEquals(Set.of("context", "aggregate", "channel"), tagKeys(loadCounters.get(0)));
		assertEquals(1, registry.get(LOAD_COUNT).counter().count(), "the registered counter should be the one that is incremented");
	}

	/** Nothing the aggregate path registers may share a name under two different sets of tag keys. */
	@Test
	void noMeterNameCarriesTwoDifferentTagKeySets ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		Mock domain = domainWithAggregate(registry);
		MockAggregate aggregate = domain.aggregate(MockAggregate.class, Tags.of("businessObject", "123"));
		aggregate.doSomething();
		domain.aggregate(MockAggregate.class, Tags.of("businessObject", "456"));

		Map<String,Set<Set<String>>> keySetsByName = new HashMap<>();
		registry.getMeters().forEach(meter ->
			keySetsByName.computeIfAbsent(meter.getId().getName(), name -> new HashSet<>()).add(tagKeys(meter)));

		String offenders = keySetsByName.entrySet().stream()
				.filter(entry -> entry.getValue().size() > 1)
				.map(entry -> "%s -> %s".formatted(entry.getKey(), new TreeSet<>(entry.getValue().stream().map(TreeSet::new).map(Object::toString).toList())))
				.collect(Collectors.joining(", "));

		assertTrue(offenders.isEmpty(), "meter names registered under several tag key sets: " + offenders);
	}

	private Mock domainWithAggregate ( SimpleMeterRegistry registry ) {
		return buildBoundedContext(
				BoundedContext.newBuilder(Mock.class)
					.name("UnitTestBoundedContext")
					.eventStorage(eventStorage())
					.meterRegistry(registry)
					.instance(InstanceFactory.determine("unittests"))
					.aggregate(MockAggregate.class)
					.done());
	}

	private static Set<String> tagKeys ( Meter meter ) {
		return meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey).collect(Collectors.toSet());
	}

	private static String ids ( List<Meter> meters ) {
		return meters.stream().map(meter -> meter.getId().toString()).collect(Collectors.joining(", "));
	}

}
