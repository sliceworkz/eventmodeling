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
package org.sliceworkz.eventmodeling.examples.banking.features.inboundtraffic;

import java.util.HashMap;
import java.util.Map;

import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingInboundEvent.CustomerSuspended;
import org.sliceworkz.eventmodeling.readmodels.PublishingReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * Everything the bank received from outside, counted per event type and per customer — a read model over
 * the <em>inbound</em> stream, projected in the background.
 *
 * <p>It is declared over {@link BankingInboundEvent} rather than the domain events, and that is all that sets
 * it apart: the slice registers it on {@code builder.inbound()}, and {@code build()} holds the declared type to
 * the stream it is registered on. It sees every inbound event, the ones no translator claims included — which
 * is what makes it the place to answer "what came in", where a read model over the domain events only sees
 * what a translator made of it.
 *
 * <p>A read model over the inbound stream reads, and decides nothing: a command's decision models read the
 * domain stream, the one its append is checked against.
 */
public class InboundTrafficReadModel extends PublishingReadModel<BankingInboundEvent, InboundTrafficReadModel.Traffic> {

	/** The instance the slice registers, and that {@code banking.inbound().read(InboundTrafficReadModel.class)} hands back. */
	public static final InboundTrafficReadModel INSTANCE = new InboundTrafficReadModel();

	@Override
	protected Traffic initialState ( ) {
		return new Traffic(Map.of(), Map.of());
	}

	@Override
	protected Traffic apply ( Traffic traffic, Event<BankingInboundEvent> event ) {
		Map<String, Long> perType = new HashMap<>(traffic.perType());
		perType.merge(EventType.of(event.data().getClass()).name(), 1L, Long::sum);
		Map<String, Long> perCustomer = new HashMap<>(traffic.perCustomer());
		switch ( event.data() ) {
			case CustomerSuspended suspended -> perCustomer.merge(suspended.customerId().value(), 1L, Long::sum);
		}
		return new Traffic(Map.copyOf(perType), Map.copyOf(perCustomer));
	}

	@Override
	public EventFilter eventFilter ( ) {
		return EventFilter.matchAll();
	}

	/**
	 * What came in, as of the position {@code published()} hands back beside it.
	 *
	 * @param perType how many inbound events of each type
	 * @param perCustomer how many inbound events were about each customer
	 */
	public record Traffic ( Map<String, Long> perType, Map<String, Long> perCustomer ) { }

}
