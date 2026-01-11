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
package org.sliceworkz.eventmodeling.boundedcontext;

import java.util.ServiceLoader;

public interface BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> extends AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	/**
	 * Returns the name of this bounded context.
	 * <p>
	 * The name is used for identifying the bounded context in logging, metrics,
	 * and event stream organization.
	 *
	 * @return the bounded context name
	 */
	String name ( );

	@SuppressWarnings("unchecked")
	public static <DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> BoundedContextBuilder<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> newBuilder ( 
			Class<DOMAIN_EVENT_TYPE> domainEventRootType,
			Class<INBOUND_EVENT_TYPE> inboundEventRootType,
			Class<OUTBOUND_EVENT_TYPE> outboundEventRootType
			) {
		 var result = ServiceLoader.load(BoundedContextBuilder.class).findFirst().get();
		 result.eventTypes(domainEventRootType, inboundEventRootType, outboundEventRootType);
		 return result;
	}

}
