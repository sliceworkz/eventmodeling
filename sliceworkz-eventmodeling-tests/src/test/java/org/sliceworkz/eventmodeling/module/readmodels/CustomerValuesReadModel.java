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
package org.sliceworkz.eventmodeling.module.readmodels;

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventmodeling.domain.Entity;
import org.sliceworkz.eventmodeling.domain.EntityId;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.readmodels.CacheKeys;
import org.sliceworkz.eventmodeling.readmodels.CachePolicy;
import org.sliceworkz.eventmodeling.readmodels.CachingReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * The values a customer's events carried, in order. {@code ThirdDomainEvent} is not relevant, and a
 * value {@code "boom"} makes the fold throw.
 */
public class CustomerValuesReadModel extends CachingReadModel<MockDomainEvent, CustomerValuesReadModel.CustomerId, List<String>> {

	public record CustomerId ( String value ) implements EntityId { }

	public static final Entity<CustomerId> CUSTOMER = Entity.of("customer", CustomerId::new);

	public CustomerValuesReadModel ( CachePolicy policy ) {
		super(CacheKeys.byEntity(CUSTOMER), policy);
	}

	public CustomerValuesReadModel ( ) {
		this(CachePolicy.maxEntries(100));
	}

	@Override
	public EventFilter eventFilter ( ) {
		return EventFilter.forTypes(FirstDomainEvent.class, SecondDomainEvent.class);
	}

	@Override
	protected List<String> initialState ( CustomerId key ) {
		return List.of();
	}

	@Override
	protected List<String> apply ( List<String> values, Event<MockDomainEvent> event ) {
		String value = switch ( event.data() ) {
			case FirstDomainEvent f -> f.value();
			case SecondDomainEvent s -> s.value();
			default -> throw new IllegalArgumentException("not relevant: " + event.data());
		};
		if ( "boom".equals(value) ) {
			throw new IllegalStateException("cannot fold boom");
		}
		List<String> next = new ArrayList<>(values);
		next.add(value);
		return List.copyOf(next);
	}

}
