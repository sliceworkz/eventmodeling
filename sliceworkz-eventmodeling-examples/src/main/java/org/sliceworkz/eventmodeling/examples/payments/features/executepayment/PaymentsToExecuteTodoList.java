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
package org.sliceworkz.eventmodeling.examples.payments.features.executepayment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentId;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAbandoned;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentAttemptFailed;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentExecuted;
import org.sliceworkz.eventmodeling.examples.payments.PaymentsDomain.PaymentsDomainEvent.PaymentRequested;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;

/**
 * The payments that still have to be sent, in the order they were requested.
 * <p>
 * This is the queue. There is no other one: the framework keeps no list of outstanding work beside this
 * read model, which is why everything the automation decides has to end up as an event this projection
 * can see. Three things worth copying out of here:
 * <ol>
 *   <li><strong>Removal is projected, never called.</strong> An item leaves because a
 *       {@code PaymentExecuted} or {@code PaymentAbandoned} was appended, so it stays gone after a
 *       restart. Removing it from the map without an event would bring it straight back, because this
 *       read model is rebuilt from the event history every time the process starts.</li>
 *   <li><strong>{@code streamItems} withholds what is not due.</strong> That is the whole of
 *       "retry later with a delay" — a deferred item is still on the list and still counted, it is
 *       simply not offered until its due time. The framework has no such mechanism and needs none.</li>
 *   <li><strong>Order is this method's business.</strong> Items are handled in the order returned here,
 *       and the framework neither re-orders them nor checks. Payments are independent of one another,
 *       so requested-order is a convenience rather than a constraint — which is exactly why this
 *       automation can afford {@code CONTINUE_AND_RETRY_ITEM_LATER}.</li>
 * </ol>
 */
public class PaymentsToExecuteTodoList implements TodoListReadModel<PaymentsDomainEvent,PaymentsToExecuteTodoList.PaymentToExecute> {

	/**
	 * One outstanding payment, carrying what the automation needs to decide what to do with it.
	 *
	 * @param attempts how many attempts have already failed in a way worth recording. Projected from
	 *        {@code PaymentAttemptFailed}, so it survives restarts — an in-memory counter would reset
	 *        and the payment would be retried forever
	 * @param dueAt when it may be attempted again, or {@code null} when it is due now
	 */
	public record PaymentToExecute ( PaymentId paymentId, String iban, long amountInCents, int attempts, Instant dueAt ) {

		public boolean isDue ( Instant now ) {
			return dueAt == null || !dueAt.isAfter(now);
		}
	}

	// LinkedHashMap: requested order is the handling order
	private final Map<String,PaymentToExecute> outstanding = new LinkedHashMap<>();
	private EventReference lastEventReference;

	@Override
	public EventQuery eventQuery ( ) {
		return EventQuery.forEvents(
			EventTypesFilter.of(PaymentRequested.class, PaymentExecuted.class, PaymentAttemptFailed.class, PaymentAbandoned.class),
			Tags.none());
	}

	@Override
	public void when ( Event<PaymentsDomainEvent> event ) {
		switch ( event.data() ) {
			case PaymentRequested requested -> outstanding.put(requested.paymentId().value(),
					new PaymentToExecute(requested.paymentId(), requested.iban(), requested.amountInCents(), 0, null));

			case PaymentExecuted executed -> outstanding.remove(executed.paymentId().value());

			case PaymentAbandoned abandoned -> outstanding.remove(abandoned.paymentId().value());

			case PaymentAttemptFailed failed -> {
				PaymentToExecute current = outstanding.get(failed.paymentId().value());
				if ( current != null ) {
					// still outstanding, just not due yet
					outstanding.put(failed.paymentId().value(), new PaymentToExecute(
							current.paymentId(), current.iban(), current.amountInCents(), failed.attempt(), failed.nextAttemptDueAt()));
				}
			}
		}
		lastEventReference = event.reference();
	}

	@Override
	public Stream<PaymentToExecute> streamItems ( Limit limit ) {
		Instant now = Instant.now();
		List<PaymentToExecute> due = new ArrayList<>();
		for ( PaymentToExecute payment : outstanding.values() ) {
			if ( payment.isDue(now) ) {
				due.add(payment);
				if ( due.size() >= limit.value() ) {
					break;
				}
			}
		}
		// a copy, not a view: the automation reads this while the projector may be updating the map
		return due.stream();
	}

	@Override
	public Optional<EventReference> lastEventReference ( ) {
		return Optional.ofNullable(lastEventReference);
	}

	/** How much work is outstanding, due or not — for the example's output. */
	public int outstandingCount ( ) {
		return outstanding.size();
	}

}
