/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.mock.boundedcontext.AbstractMockDomainTest;
import org.sliceworkz.eventmodeling.mock.boundedcontext.InvocationCountingEventStorage;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockBoundedContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;

public class InboundModuleTest  extends AbstractMockDomainTest {
	
	private EventStorage rawEventStorage;
	private InvocationCountingEventStorage eventStorage;
	
	@BeforeEach
	protected void setUp ( ) {
		super.setUp();
		this.rawEventStorage = createEventStorage();
		this.eventStorage = new InvocationCountingEventStorage(rawEventStorage);
	}
	
	@AfterEach
	protected void tearDown ( ) {
		destroyEventStorage(rawEventStorage);
		boundedContext().stop();
	}
	
	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().build();
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}

	
	MockBoundedContext createBoundedContext( ) {
		
		var builder = BoundedContext.newBuilder(MockDomainEvent.class, MockInboundEvent.class, MockOutboundEvent.class)
			.name("UnitTestBoundedContext")
			.eventStorage(eventStorage)
			.instance(InstanceFactory.determine("unittests"));

		return buildBoundedContext ( builder );
	}
}
