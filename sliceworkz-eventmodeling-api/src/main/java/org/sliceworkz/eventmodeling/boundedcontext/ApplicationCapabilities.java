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

import org.sliceworkz.eventmodeling.aggregates.AggregateCapability;
import org.sliceworkz.eventmodeling.commands.CommandExecutionCapability;
import org.sliceworkz.eventmodeling.readmodels.ReadModelCapability;

/**
 * What application code may do with a bounded context: decide and read.
 * <p>
 * This is the surface a controller, a scheduled job or any adapter driving the domain should hold.
 * It carries the two write paths that go through a consistency boundary — {@link
 * CommandExecutionCapability#execute executing a command}, which pins its boundary at the domain
 * stream's head and re-checks it on append, and {@link AggregateCapability#aggregate loading an
 * aggregate}, which raises through its own identity — together with {@link
 * ReadModelCapability#read reading a read model}. It carries nothing else.
 *
 * <h2>Holding it</h2>
 * A built context already is one, because {@link AllCapabilities} extends this interface, so
 * narrowing costs a reference type and nothing more:
 * <pre>{@code
 * @Bean
 * ApplicationCapabilities<BankingDomainEvent, BankingOutboundEvent> bankingApp ( Banking banking ) {
 *     return banking;
 * }
 * }</pre>
 * Declaring an interface of your own over it drops the type arguments from every call site, exactly
 * as the context interface does:
 * <pre>{@code
 * public interface BankingApi extends ApplicationCapabilities<BankingDomainEvent, BankingOutboundEvent> { }
 * public interface Banking extends BoundedContext<BankingDomainEvent, BankingInboundEvent, BankingOutboundEvent>, BankingApi { }
 * }</pre>
 *
 * <h2>What it deliberately leaves out</h2>
 * <ul>
 * <li>{@link org.sliceworkz.eventmodeling.events.ProvidedEventCapability#event event(...)} — appending
 *     a domain event that no command raised, so no decision model was read and no boundary was
 *     checked. A deliberate escape hatch for the cases that want it, and not something application
 *     code should reach by accident.</li>
 * <li>{@link LifecycleCapability} — {@code start()}, {@code stop()} and {@code terminate()} belong to
 *     whoever built the context.</li>
 * <li>{@link OperationsCapabilities} and {@link PrivacyCapability} — an operator's surface and an
 *     erasure request are each their own audience.</li>
 * <li>{@link org.sliceworkz.eventmodeling.inbound.TranslationCapability} — feeding the domain
 *     external events is the inbound edge's job; an adapter that does both holds both.</li>
 * </ul>
 * <p>
 * This is a boundary of discipline, not of security: the object behind the reference is still the
 * whole context, and a cast reaches it. See {@code WHO-MAY-DO-WHAT.md}.
 *
 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
 * @param <OUTBOUND_EVENT_TYPE> the bounded context's outbound event type
 */
public interface ApplicationCapabilities<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> extends
	CommandExecutionCapability<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE>,
	ReadModelCapability<DOMAIN_EVENT_TYPE>,
	AggregateCapability<DOMAIN_EVENT_TYPE> {

}
