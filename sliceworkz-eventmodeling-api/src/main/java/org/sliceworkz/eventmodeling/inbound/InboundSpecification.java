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
package org.sliceworkz.eventmodeling.inbound;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.readmodels.EventuallyConsistentReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.LiveModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;

/**
 * What a bounded context is configured with on its inbound stream: the stream the external events it
 * receives are appended to, {@code builder.inbound()}.
 * <p>
 * Everything here reads the inbound stream: a {@link Translator} turns what came in into domain events,
 * and a read model registered here is projected from the inbound events rather than from the domain
 * events — what came in, counted per type or per tag value, whether it was ever translated or not:
 * <pre>{@code
 * public void configureAutomation ( BoundedContextBuilder<Banking> builder ) {
 *     builder.inbound().translator(new PaymentReceivedTranslator());
 * }
 *
 * public void configureQuery ( BoundedContextBuilder<Banking> builder ) {
 *     builder.inbound().readmodel(new InboundTrafficReadModel()).eventuallyConsistent();
 * }
 * }</pre>
 * Registering a read model ends on the bounded context builder, as it does on the builder itself.
 * <p>
 * Like every registration on the builder, these are wildcard-typed: {@code build()} checks the declared
 * inbound event type of each against this context's, and refuses one that is unrelated to it.
 *
 * @param <C> the bounded context type
 */
public interface InboundSpecification<C extends BoundedContext<?,?,?>> {

	/**
	 * The root of the inbound events this context's history holds under a legacy shape, each upcast into
	 * a current inbound event on the read. See the eventstore's {@code @LegacyEvent} and {@code Upcaster}.
	 *
	 * @param legacyInboundEventRootType a sealed interface over the legacy inbound event records
	 * @return this specification
	 */
	InboundSpecification<C> legacyEventTypes ( Class<?> legacyInboundEventRootType );

	/**
	 * Registers a translator, which turns the inbound events matching its filter into domain events.
	 *
	 * @return this specification
	 * @throws IllegalArgumentException from {@code build()} when its declared inbound or domain event type is
	 *         unrelated to this context's
	 */
	InboundSpecification<C> translator ( Translator<?,?> translator );

	/**
	 * Registers a translator this builder constructs, for one that needs nothing passed to it.
	 *
	 * @param translatorClass a concrete class with a public no-argument constructor
	 * @return this specification
	 * @throws IllegalArgumentException if the class cannot be instantiated -- it is abstract or an
	 *         interface, has no public no-argument constructor (what a non-static inner class produces),
	 *         or its constructor threw. The message names the class and the remedy, and a constructor's
	 *         own throwable is the cause. Register an instance instead where the translator takes
	 *         constructor arguments
	 */
	InboundSpecification<C> translator ( Class<? extends Translator<?,?>> translatorClass );

	/**
	 * Registers a read model over the inbound stream, built afresh for each read from that read's
	 * parameters. Say how it is projected with {@link LiveModelSpecification#live()}, and read it through
	 * {@code context.inbound().read(...)}.
	 *
	 * @throws IllegalArgumentException from {@code build()} when its declared event type is unrelated to
	 *         this context's inbound event type
	 */
	LiveModelSpecification<C> readmodel ( Class<? extends ReadModel<?>> readModelClass );

	/**
	 * Registers a read model over the inbound stream as the one long-lived instance a processor of its
	 * own projects in the background. Say so with
	 * {@link EventuallyConsistentReadModelSpecification#eventuallyConsistent()}, and read it through
	 * {@code context.inbound().read(...)}.
	 *
	 * @throws IllegalArgumentException from {@code build()} when its declared event type is unrelated to
	 *         this context's inbound event type
	 */
	EventuallyConsistentReadModelSpecification<C> readmodel ( ReadModel<?> readModel );

	/** Back to the bounded context builder. */
	BoundedContextBuilder<C> done ( );

}
