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
package org.sliceworkz.eventmodeling.mock.boundedcontext;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventstore.spi.EventStorage;

public abstract class AbstractBoundedContextTest<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {
	
	protected BoundedContext<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> boundedContext;
	
	@BeforeEach
	protected void setUp ( ) {
		MockReadModel.reset();
	}
	
	public abstract Class<DOMAIN_EVENT_TYPE> domainEventType ( );
	
	public abstract Class<INBOUND_EVENT_TYPE> inboundEventType ( );
	
	public abstract Class<OUTBOUND_EVENT_TYPE> outboundEventType ( );
	
	protected BoundedContext<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> boundedContext ( ) {
		return boundedContext;
	}
	
	protected BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> boundedContextBuilder ( EventStorage eventStorage ) {
		
		BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> builder = 
				BoundedContext.newBuilder(domainEventType(), inboundEventType(), outboundEventType());
		
		builder
			.name("UnitTestBoundedContext")
			.eventStorage(eventStorage);
		
		return builder;
	}
	
}
