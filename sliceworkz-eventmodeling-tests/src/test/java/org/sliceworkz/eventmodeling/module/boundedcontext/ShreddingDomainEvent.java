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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import org.sliceworkz.eventstore.shredding.Shreddable;

/**
 * A domain event holding two data subjects' personal data, each under its own key.
 * <p>
 * The pseudonymous customer ids stay in the clear: they are what the event is tagged and queried by,
 * and they must survive an erasure for the transfer to remain a coherent fact.
 */
public sealed interface ShreddingDomainEvent {

	/**
	 * A transfer between two customers.
	 *
	 * @param transferId     the transfer, not personal data
	 * @param cents          the amount, not personal data
	 * @param fromCustomerId pseudonymous, survives erasure
	 * @param toCustomerId   pseudonymous, survives erasure
	 * @param from           the payer's name, protected under the payer's key
	 * @param to             the payee's name, protected under the payee's key
	 */
	record TransferMade (
			String transferId,
			long cents,
			String fromCustomerId,
			String toCustomerId,
			Shreddable<String> from,
			Shreddable<String> to ) implements ShreddingDomainEvent { }

}
