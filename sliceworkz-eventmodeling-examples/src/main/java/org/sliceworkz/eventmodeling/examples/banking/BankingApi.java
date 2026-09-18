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
package org.sliceworkz.eventmodeling.examples.banking;

import org.sliceworkz.eventmodeling.boundedcontext.ApplicationCapabilities;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingOutboundEvent;

/**
 * What application code may do with the banking context: execute commands and read read models.
 * <p>
 * This is what a controller, a scheduled job or any adapter driving the domain should be handed —
 * not {@link Banking}, which also erases people, stops automations, appends domain events no command
 * raised, and terminates the context. Narrowing costs exactly this declaration plus a reference type
 * at the call site: {@link Banking} extends it, so a built context already is one.
 * <p>
 * The interface exists only to drop the type arguments from every call site, exactly as {@code
 * Banking} does for {@code BoundedContext} — {@code ApplicationCapabilities<BankingDomainEvent,
 * BankingOutboundEvent>} would do just as well and needs no declaration at all.
 * <p>
 * See {@code WHO-MAY-DO-WHAT.md} for the other audiences and the reasoning.
 */
public interface BankingApi extends ApplicationCapabilities<BankingDomainEvent, BankingOutboundEvent> {

}
