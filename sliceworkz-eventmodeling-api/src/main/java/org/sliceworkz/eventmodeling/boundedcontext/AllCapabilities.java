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

import org.sliceworkz.eventmodeling.events.ProvidedEventCapability;
import org.sliceworkz.eventmodeling.inbound.TranslationCapability;

/**
 * Everything a bounded context can do, which is what its owner holds.
 * <p>
 * {@link BoundedContext} extends this, so the handle {@code build()} returns carries the whole
 * surface — and only the code that built it has a use for all of it. Every other caller should hold
 * one of the narrower interfaces this one composes, so that what a reference may do says who is
 * holding it:
 *
 * <table border="1">
 * <caption>The audiences</caption>
 * <tr><th>audience</th><th>interface</th><th>what it carries</th></tr>
 * <tr><td>application — a controller, a job, an adapter driving the domain</td>
 *     <td>{@link ApplicationCapabilities}</td><td>{@code execute}, {@code executeWithRetry},
 *     {@code read}, {@code aggregate}</td></tr>
 * <tr><td>inbound edge — a webhook, a consumer feeding the domain</td>
 *     <td>{@link TranslationCapability}</td><td>{@code incoming}, {@code translate}</td></tr>
 * <tr><td>operator — an admin endpoint, a dashboard</td>
 *     <td>{@link OperationsCapabilities}</td><td>{@code automations}, {@code processors} and their
 *     {@code restart}/{@code stop}</td></tr>
 * <tr><td>erasure requests</td><td>{@link PrivacyCapability}</td>
 *     <td>{@code erase}, {@code eraseCategory}</td></tr>
 * <tr><td>the escape hatch</td><td>{@link ProvidedEventCapability}</td>
 *     <td>{@code event} — a domain event no command raised</td></tr>
 * <tr><td>owner — whatever built the context</td><td>this interface</td>
 *     <td>the above, plus {@link LifecycleCapability}, {@link PortsCapability} and
 *     {@link FeatureSliceCapabilities}</td></tr>
 * </table>
 * <p>
 * Narrowing costs a reference type and nothing else — a built context already is each of these — and
 * it is a boundary of discipline rather than of security, since the object behind the reference is
 * still the whole context. {@code WHO-MAY-DO-WHAT.md} carries the reasoning and the worked shapes.
 *
 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
 * @param <INBOUND_EVENT_TYPE> the bounded context's inbound event type
 * @param <OUTBOUND_EVENT_TYPE> the bounded context's outbound event type
 */
public interface AllCapabilities<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> extends
	ApplicationCapabilities<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE>,
	TranslationCapability<INBOUND_EVENT_TYPE>,
	OperationsCapabilities,
	ProvidedEventCapability<DOMAIN_EVENT_TYPE>,
	PrivacyCapability,
	LifecycleCapability,
	PortsCapability,
	FeatureSliceCapabilities {

}
