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

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateSpecification;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.LiveModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.LongLivedReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.spi.EventStorage;

import io.micrometer.core.instrument.MeterRegistry;

public interface BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> name(String name);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> eventTypes(
			Class<DOMAIN_EVENT_TYPE> domainEventRootType,
			Class<INBOUND_EVENT_TYPE> inboundEventRootType,
			Class<OUTBOUND_EVENT_TYPE> outboundEventRootType);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> historicalEventTypes(
			Class<?> historicalDomainEventRootType,
			Class<?> historicalInboundEventRootType,
			Class<?> historicalOutboundEventRootType);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> instance(Instance instance);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> meterRegistry(MeterRegistry meterRegistry);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> eventStorage(EventStorage eventStorage);

	FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> features ( );

	AggregateSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> aggregate(Class<? extends Aggregate<DOMAIN_EVENT_TYPE>> aggregateClass);

	LiveModelSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> readmodel(Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass);

	LongLivedReadModelSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> readmodel(ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel);

	<TODO_ITEM_TYPE> BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> automation(
			Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> translator(
			Translator<? extends INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> translator);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> translator(
			Class<? extends Translator<? extends INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> translatorClass);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> dispatcher(
			Dispatcher<? extends OUTBOUND_EVENT_TYPE> dispatcher);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> dispatcher(
			Class<? extends Dispatcher<? extends OUTBOUND_EVENT_TYPE>> dispatcherClass);

	<T extends BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> T build( );

	<T extends BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> T build(Class<T> returnType);

}