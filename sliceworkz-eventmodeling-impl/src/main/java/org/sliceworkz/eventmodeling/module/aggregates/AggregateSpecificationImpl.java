package org.sliceworkz.eventmodeling.module.aggregates;

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateSpecification;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;

public class AggregateSpecificationImpl<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> implements AggregateSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	private BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> parent;
	
	private Class<? extends Aggregate<DOMAIN_EVENT_TYPE>> aggregateClass;

	public AggregateSpecificationImpl ( BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> parent, Class<? extends Aggregate<DOMAIN_EVENT_TYPE>> aggregateClass ) {
		this.parent = parent;
		this.aggregateClass = aggregateClass;
	}
	
	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> done() {
		return parent;
	}
	
	public Class<? extends Aggregate<DOMAIN_EVENT_TYPE>> aggregateClass ( ) {
		return aggregateClass;
	}
	
}
