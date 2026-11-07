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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingInboundEvent.CustomerSuspended;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.CustomerId;
import org.sliceworkz.eventmodeling.examples.banking.features.inboundtraffic.InboundTrafficReadModel.Traffic;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.testing.InboundLiveModelTest;

/**
 * The worked example of testing a read model over the inbound stream: {@link InboundLiveModelTest} seeds
 * inbound events as they would have come in, and reads the read model through {@code inbound().read(...)}.
 * <p>
 * The slice registers it eventually consistent; the test projects it live, which folds the same events
 * through the same {@code apply} without a background thread to wait for.
 */
public class InboundTrafficReadModelTest extends InboundLiveModelTest<BankingDomainEvent, BankingInboundEvent, BankingOutboundEvent> {

	private static final CustomerId ALICE = BankingDomain.CUSTOMER.id("alice");
	private static final CustomerId BOB = BankingDomain.CUSTOMER.id("bob");

	@Override
	public Class<? extends ReadModel<BankingInboundEvent>> getLiveModelClass ( ) {
		return InboundTrafficReadModel.class;
	}

	@Override
	public Class<BankingDomainEvent> domainEventType ( ) {
		return BankingDomainEvent.class;
	}

	@Override
	public Class<BankingInboundEvent> inboundEventType ( ) {
		return BankingInboundEvent.class;
	}

	@Override
	public Class<BankingOutboundEvent> outboundEventType ( ) {
		return BankingOutboundEvent.class;
	}

	@Test
	void countsWhatCameInPerTypeAndPerCustomer ( ) {
		given()
			.events(new CustomerSuspended(ALICE), new CustomerSuspended(BOB), new CustomerSuspended(ALICE))
		.then()
			.liveModel(model -> ((InboundTrafficReadModel) model).published().data())
			.is(new Traffic(Map.of("CustomerSuspended", 3L), Map.of("alice", 2L, "bob", 1L)));
	}

	@Test
	void nothingCameIn ( ) {
		given()
		.then()
			.liveModel(model -> ((InboundTrafficReadModel) model).published().data())
			.is(new Traffic(Map.of(), Map.of()));
	}

}
