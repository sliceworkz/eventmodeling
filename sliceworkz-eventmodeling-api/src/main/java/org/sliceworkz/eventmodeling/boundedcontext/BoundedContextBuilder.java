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

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateSpecification;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.LiveModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.LongLivedReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.slices.FeatureSliceConfiguration;
import org.sliceworkz.eventstore.spi.EventStorage;

import io.micrometer.core.instrument.MeterRegistry;

public interface BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> name(String name);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> eventTypes(
			Class<DOMAIN_EVENT_TYPE> domainEventRootType, 
			Class<INBOUND_EVENT_TYPE> inboundEventRootType,
			Class<OUTBOUND_EVENT_TYPE> outboundEventRootType);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> instance(Instance instance);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> meterRegistry(MeterRegistry meterRegistry);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> featureDeployFilter(Predicate<FeatureSliceConfiguration<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> featureDeployFilter);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> eventStorage(EventStorage eventStorage);

	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> rootPackage(Package rootPackage);

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
	 * Registers a pre-configuration callback that will be invoked for each feature slice
	 * before it is configured.
	 * <p>
	 * This allows customization of feature slice configurations before they are built into
	 * the bounded context. The callback receives the feature slice configuration and can
	 * modify its settings.
	 *
	 * @param preConfigure the callback to invoke for each feature slice configuration
	 * @return this builder for method chaining
	 */
	BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> preConfigure (
			Consumer<FeatureSliceConfiguration<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> preConfigure );

	<T extends BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> T build( );

	<T extends BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> T build(Class<T> returnType);

}