package org.sliceworkz.eventmodeling.aggregates;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;

public interface AggregateSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> done ( );
	
}
