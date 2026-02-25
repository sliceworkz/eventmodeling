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

	/**
	 * Starts an adapter binding for the given adapter instance.
	 * <p>
	 * The returned {@link AdapterBinding} must be completed by calling
	 * {@link AdapterBinding#forPort(Class)} to specify the port type.
	 * <p>
	 * Example:
	 * <pre>
	 *   .adapter(myDataSource).forPort(DataSource.class)
	 *   .adapter(myCache).forPort(Cache.class).withQualification("customers")
	 * </pre>
	 *
	 * @param adapter the adapter instance implementing a port
	 * @return an adapter binding to specify the port type
	 */
	AdapterBinding<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> adapter(Object adapter);

	/**
	 * Retrieves the adapter registered for the given port type with the default qualification.
	 *
	 * @param <T> the port type
	 * @param portType the port interface class
	 * @return the adapter instance, cast to the port type
	 * @throws IllegalStateException if no adapter is registered for this port
	 */
	<T> T port(Class<T> portType);

	/**
	 * Retrieves the adapter registered for the given port type and qualification.
	 *
	 * @param <T> the port type
	 * @param portType the port interface class
	 * @param qualification the qualification name
	 * @return the adapter instance, cast to the port type
	 * @throws IllegalStateException if no adapter is registered for this port and qualification
	 */
	<T> T port(Class<T> portType, String qualification);

	<T extends BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> T build( );

	<T extends BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> T build(Class<T> returnType);

}