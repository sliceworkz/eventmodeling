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
package org.sliceworkz.eventmodeling.outbound;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.readmodels.EventuallyConsistentReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.LiveModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;

/**
 * What a bounded context is configured with on its outbound stream: the integration events it tells the
 * world, {@code builder.outbound()}.
 * <p>
 * A {@link Publisher} is the only writer of the outbound stream — it reads domain events and maps them
 * into outbound events, which is why it is registered here although it reads the domain stream — and a
 * {@link Dispatcher} is its reader that sends them off the store. A read model registered here is
 * projected from the outbound events: what went out, counted per type or per tag value:
 * <pre>{@code
 * public void configureAutomation ( BoundedContextBuilder<Planning> builder ) {
 *     builder.readmodel(PlannedSessionReadModel.class).live();
 *     builder.outbound()
 *            .publisher(new PlannedSessionPublisher())
 *            .dispatcher(new PlannedSessionPublishedDispatcher(builder.port(MessageBusPublisher.class)));
 * }
 * }</pre>
 * Registering a read model ends on the bounded context builder, as it does on the builder itself.
 * <p>
 * Like every registration on the builder, these are wildcard-typed: {@code build()} checks the declared
 * event types of each against this context's, and refuses one that is unrelated to them.
 *
 * @param <C> the bounded context type
 */
public interface OutboundSpecification<C extends BoundedContext<?,?,?>> {

	/**
	 * The root of the outbound events this context's history holds under a legacy shape, each upcast into
	 * a current outbound event on the read. See the eventstore's {@code @LegacyEvent} and {@code Upcaster}.
	 *
	 * @param legacyOutboundEventRootType a sealed interface over the legacy outbound event records
	 * @return this specification
	 */
	OutboundSpecification<C> legacyEventTypes ( Class<?> legacyOutboundEventRootType );

	/**
	 * Registers a publisher, which maps the domain events matching its filter into outbound events.
	 * <p>
	 * A publisher is part of a slice's automation aspect: register it from
	 * {@link org.sliceworkz.eventmodeling.slices.Slice#configureAutomation configureAutomation}, together
	 * with the live read models it {@linkplain Publisher#reads() reads}, so it runs on the instances that
	 * deploy automations and finds its read models there.
	 *
	 * @return this specification
	 * @throws IllegalArgumentException from {@code build()} when it is registered while a slice configures
	 *         another aspect than automation, when a read model it declares is not registered {@code .live()}
	 *         on this instance, when its name cannot key a bookmark, or when its declared domain or outbound
	 *         event type is unrelated to this context's
	 */
	OutboundSpecification<C> publisher ( Publisher<?,?> publisher );

	/**
	 * Registers a dispatcher, the outbound stream's reader and the only thing sending it off the store.
	 *
	 * @return this specification
	 * @throws IllegalArgumentException from {@code build()} when its declared outbound event type is
	 *         unrelated to this context's
	 */
	OutboundSpecification<C> dispatcher ( Dispatcher<?> dispatcher );

	/**
	 * Registers a dispatcher this builder constructs, for one that needs nothing passed to it.
	 *
	 * @param dispatcherClass a concrete class with a public no-argument constructor
	 * @return this specification
	 * @throws IllegalArgumentException if the class cannot be instantiated -- it is abstract or an
	 *         interface, has no public no-argument constructor (what a non-static inner class produces),
	 *         or its constructor threw. The message names the class and the remedy, and a constructor's
	 *         own throwable is the cause. Register an instance instead where the dispatcher takes
	 *         constructor arguments
	 */
	OutboundSpecification<C> dispatcher ( Class<? extends Dispatcher<?>> dispatcherClass );

	/**
	 * Registers a read model over the outbound stream, built afresh for each read from that read's
	 * parameters. Say how it is projected with {@link LiveModelSpecification#live()}, and read it through
	 * {@code context.outbound().read(...)}.
	 *
	 * @throws IllegalArgumentException from {@code build()} when its declared event type is unrelated to
	 *         this context's outbound event type
	 */
	LiveModelSpecification<C> readmodel ( Class<? extends ReadModel<?>> readModelClass );

	/**
	 * Registers a read model over the outbound stream as the one long-lived instance a processor of its
	 * own projects in the background. Say so with
	 * {@link EventuallyConsistentReadModelSpecification#eventuallyConsistent()}, and read it through
	 * {@code context.outbound().read(...)}.
	 *
	 * @throws IllegalArgumentException from {@code build()} when its declared event type is unrelated to
	 *         this context's outbound event type
	 */
	EventuallyConsistentReadModelSpecification<C> readmodel ( ReadModel<?> readModel );

	/** Back to the bounded context builder. */
	BoundedContextBuilder<C> done ( );

}
