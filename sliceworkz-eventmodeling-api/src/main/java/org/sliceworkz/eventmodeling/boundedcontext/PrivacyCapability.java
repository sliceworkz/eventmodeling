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

import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.ErasureReport;
import org.sliceworkz.eventstore.shredding.Shreddable;

/**
 * Erasing a person's data from a bounded context.
 * <p>
 * A record component declared {@link Shreddable} is encrypted on append under a key held for its
 * {@link DataSubject}. Erasing the subject destroys those keys, which makes every value sealed under
 * them permanently unreadable — in the events table, in the write-ahead log, on replicas and in every
 * backup — while the events themselves stay byte-identical. Nothing in the log is rewritten, so
 * ordering, bookmarks and consistency boundaries are untouched.
 *
 * <h2>Handling an erasure request</h2>
 * <pre>{@code
 * ErasureReport report = context.erase(
 *         DataSubject.of("customer", customerId),
 *         ErasureReason.of("GDPR art.17 request #4711, approved by DPO " + today));
 *
 * report.keysShredded();   // 0 when the subject held no keys -- erasure is idempotent
 * }</pre>
 * The pseudonymous identifiers stay: a projection can still count the customer's orders and a ledger
 * still reconciles. Only what was wrapped in a {@code Shreddable} becomes unreadable, and it reads back
 * as {@link Shreddable.Shredded} rather than as null, so events whose records reject nulls keep loading.
 *
 * <h2>Read models are not erased, and will not notice</h2>
 * This is the part that needs a deliberate answer in every application, because the framework cannot
 * give one. Read models hold their own copies of whatever a projection wrote into them, and projections
 * hold bookmarks, so they never re-read the affected events. After an erasure:
 * <ul>
 *   <li>a read model that stored personal data <b>still holds it</b>, in full;</li>
 *   <li>nothing is notified, so nothing re-projects on its own;</li>
 *   <li>a rebuild from scratch fixes it, because the replay now reads the values as shredded.</li>
 * </ul>
 * So an erasure request is honoured in the event log immediately and in the read models only once they
 * are rebuilt. Either keep personal data out of read models and read it through the events, or rebuild
 * the affected read models as part of handling the request.
 *
 * <h2>Requires shredding to be configured</h2>
 * A context built without {@code BoundedContextBuilder.shredding(...)} holds no keys and cannot erase
 * anything; calling this throws. That is the same builder call that lets an event type declare a
 * {@code Shreddable} component at all — without it, registering such a type fails at startup rather
 * than storing personal data in the clear.
 *
 * @see org.sliceworkz.eventstore.shredding.Shreddable
 * @see BoundedContextBuilder#shredding(org.sliceworkz.eventstore.shredding.ShreddingKeyStore)
 */
public interface PrivacyCapability {

	/**
	 * Erases a data subject's personal data by destroying the keys that protect it.
	 * <p>
	 * Idempotent: a subject that holds no keys — never appended for, or erased already — reports
	 * {@link ErasureReport#isNoop()} rather than failing. Data appended for the subject afterwards gets
	 * a fresh key and is readable; only what was sealed under the destroyed keys is gone.
	 *
	 * @param subject whose data to erase; the unit of erasure is the data subject, not a field or an event
	 * @param reason  why, recorded alongside the destroyed key — the events record nothing about the
	 *                erasure, so this is the whole audit trail
	 * @return what was destroyed
	 * @throws UnsupportedOperationException if this context was built without shredding configured
	 * @throws org.sliceworkz.eventstore.shredding.ShreddingException if the key store cannot be reached
	 * @throws IllegalArgumentException if either argument is null
	 */
	ErasureReport erase ( DataSubject subject, ErasureReason reason );

}
