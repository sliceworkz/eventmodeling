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
package org.sliceworkz.eventmodeling.examples.payments;

import java.time.Instant;

import org.sliceworkz.eventmodeling.domain.DomainConcept;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;

/**
 * A small domain whose only job is to show an automation that talks to something outside itself, and
 * what that costs when the outside thing misbehaves.
 * <p>
 * The four domain events are the whole story of the automation pattern here:
 * <ul>
 *   <li>{@code PaymentRequested} puts work on the todo list</li>
 *   <li>{@code PaymentExecuted} takes it off again — the happy path</li>
 *   <li>{@code PaymentAttemptFailed} <em>defers</em> it: still outstanding, but not due yet</li>
 *   <li>{@code PaymentAbandoned} takes it off for good — the dead letter</li>
 * </ul>
 * The last two are the point. There is no dead-letter queue and no retry-with-delay mechanism in the
 * framework, because there does not need to be one: a todo list is projected from events, so recording
 * the failure as an event is what defers or drops the item, durably and visibly, and a read model over
 * those same events is the dead-letter view an operator looks at.
 */
public interface PaymentsDomain {

	DomainConcept CONCEPT_PAYMENT = DomainConcept.of("payment");

	sealed interface PaymentsDomainEvent {

		/** A payment has been requested and is now outstanding work for the automation. */
		record PaymentRequested ( DomainConceptId paymentId, String iban, long amountInCents ) implements PaymentsDomainEvent { }

		/** The gateway accepted the payment. The todo list drops the item on this. */
		record PaymentExecuted ( DomainConceptId paymentId, String gatewayReference ) implements PaymentsDomainEvent { }

		/**
		 * One attempt failed in a way that may still succeed later. Carries the attempt number and when
		 * the next attempt is due, both of which the todo list projects — which is how a delay survives a
		 * restart. Nothing kept outside events would.
		 */
		record PaymentAttemptFailed ( DomainConceptId paymentId, String reason, int attempt, Instant nextAttemptDueAt ) implements PaymentsDomainEvent { }

		/** Given up on. The todo list drops the item, and the dead-letter read model picks it up. */
		record PaymentAbandoned ( DomainConceptId paymentId, String reason, int attempts ) implements PaymentsDomainEvent { }

	}

	sealed interface PaymentsInboundEvent {

		/** Not used by this example; a bounded context declares three event types even so. */
		record PaymentInstructionReceived ( DomainConceptId paymentId ) implements PaymentsInboundEvent { }

	}

	sealed interface PaymentsOutboundEvent {

		/** Not used by this example; a bounded context declares three event types even so. */
		record PaymentAnnounced ( DomainConceptId paymentId ) implements PaymentsOutboundEvent { }

	}

}
