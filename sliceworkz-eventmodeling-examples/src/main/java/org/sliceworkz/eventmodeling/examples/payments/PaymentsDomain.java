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
import org.sliceworkz.eventmodeling.domain.Entity;
import org.sliceworkz.eventmodeling.domain.EntityId;


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
 * <p>
 * Around that automation sit the two edges of the context, and a read model kept in a database:
 * <ul>
 *   <li><b>in</b> — {@code PaymentInstructionReceived} arrives through {@code incoming(...)} and a
 *       translator turns it into {@code PaymentRequested}</li>
 *   <li><b>out</b> — when a payment goes through, the automation publishes {@code PaymentAnnounced} and
 *       records {@code PaymentExecuted} in one {@code publishAndRecord}, and a dispatcher delivers the
 *       announcement to the systems downstream</li>
 *   <li><b>read</b> — {@code PaymentStatusProjector} keeps every payment's status in SQL tables, for a
 *       read that has to survive the process or be shared between instances</li>
 * </ul>
 */
public interface PaymentsDomain {

	/** The identity of one payment; its record is what keeps a payment id from being handed to something expecting another kind of id. */
	record PaymentId ( String value ) implements EntityId { }

	Entity<PaymentId> PAYMENT = Entity.of("payment", PaymentId::new);

	sealed interface PaymentsDomainEvent {

		/** A payment has been requested and is now outstanding work for the automation. */
		record PaymentRequested ( PaymentId paymentId, String iban, long amountInCents ) implements PaymentsDomainEvent { }

		/** The gateway accepted the payment. The todo list drops the item on this. */
		record PaymentExecuted ( PaymentId paymentId, String gatewayReference ) implements PaymentsDomainEvent { }

		/**
		 * One attempt failed in a way that may still succeed later. Carries the attempt number and when
		 * the next attempt is due, both of which the todo list projects — which is how a delay survives a
		 * restart. Nothing kept outside events would.
		 */
		record PaymentAttemptFailed ( PaymentId paymentId, String reason, int attempt, Instant nextAttemptDueAt ) implements PaymentsDomainEvent { }

		/** Given up on. The todo list drops the item, and the dead-letter read model picks it up. */
		record PaymentAbandoned ( PaymentId paymentId, String reason, int attempts ) implements PaymentsDomainEvent { }

	}

	sealed interface PaymentsInboundEvent {

		/**
		 * An instruction from the outside world to make a payment — a partner's system, a file upload,
		 * a message on a queue. It is not a fact of this context until a translator says so:
		 * {@code PaymentInstructionTranslator} turns it into {@code PaymentRequested}.
		 */
		record PaymentInstructionReceived ( PaymentId paymentId, String iban, long amountInCents ) implements PaymentsInboundEvent { }

	}

	sealed interface PaymentsOutboundEvent {

		/**
		 * Published for the systems downstream once a payment went through. Raised by
		 * {@code AnnouncePaymentCommand} from inside the automation, in the same step that records
		 * {@code PaymentExecuted}, and delivered by {@code PaymentAnnouncementDispatcher}.
		 */
		record PaymentAnnounced ( PaymentId paymentId, String gatewayReference ) implements PaymentsOutboundEvent { }

	}

}
