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

import org.sliceworkz.eventmodeling.EventTypes; // retained for javadoc reference
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

public interface BoundedContextBuilder<C extends BoundedContext<?,?,?>> {

	BoundedContextBuilder<C> name(String name);

	BoundedContextBuilder<C> contextType(Class<C> contextType);

	BoundedContextBuilder<C> eventTypes(
			Class<?> domainEventRootType,
			Class<?> inboundEventRootType,
			Class<?> outboundEventRootType);

	BoundedContextBuilder<C> historicalEventTypes(
			Class<?> historicalDomainEventRootType,
			Class<?> historicalInboundEventRootType,
			Class<?> historicalOutboundEventRootType);

	BoundedContextBuilder<C> instance(Instance instance);

	BoundedContextBuilder<C> meterRegistry(MeterRegistry meterRegistry);

	BoundedContextBuilder<C> eventStorage(EventStorage eventStorage);

	FeaturesSpecification<C> features ( );

	/**
	 * Registers a listener notified of {@link BoundedContextEvent}s produced by the kernel of this
	 * bounded context (lifecycle, commands executed, read models updated, ...).
	 * <p>
	 * When no listener is registered no kernel events are produced and there is no overhead. Two
	 * ready-to-use implementations are provided: {@link StreamAppendingBoundedContextListener} and
	 * {@link LoggingBoundedContextListener}.
	 *
	 * @param listener the listener to notify; must not be {@code null}
	 * @return this builder
	 */
	BoundedContextBuilder<C> listener ( BoundedContextListener listener );

	AggregateSpecification<C> aggregate(Class<? extends Aggregate<?>> aggregateClass);

	LiveModelSpecification<C> readmodel(Class<? extends ReadModelWithMetaData<?>> readModelClass);

	LongLivedReadModelSpecification<C> readmodel(ReadModelWithMetaData<?> readModel);

	BoundedContextBuilder<C> automation(Automation<?,?,?> automation);

	BoundedContextBuilder<C> translator(Translator<?,?> translator);

	BoundedContextBuilder<C> translator(Class<? extends Translator<?,?>> translatorClass);

	BoundedContextBuilder<C> dispatcher(Dispatcher<?> dispatcher);

	BoundedContextBuilder<C> dispatcher(Class<? extends Dispatcher<?>> dispatcherClass);

	/**
	 * Starts an adapter binding for the given adapter instance.
	 * <p>
	 * The returned {@link AdapterBinding} must be completed by calling
	 * {@link AdapterBinding#forPort(Class)} to specify the port type.
	 * <p>
	 * Example:
	 * <pre>
	 *   .adapter(myDataSource).forPort(DataSource.class)
	 *   .adapter(myCache).forPort(Cache.class, "customers")
	 * </pre>
	 *
	 * @param adapter the adapter instance implementing a port
	 * @return an adapter binding to specify the port type
	 */
	AdapterBinding<C> adapter(Object adapter);

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

	C build();

}
