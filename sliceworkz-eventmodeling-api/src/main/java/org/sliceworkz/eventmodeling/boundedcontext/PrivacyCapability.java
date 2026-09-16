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
import org.sliceworkz.eventstore.shredding.SubjectErasureReport;

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
 * A {@link DataSubject} always names a <i>category</i> of the person's data — {@code DataSubject.of(type, id)}
 * is the {@code default} one — and {@link #erase} destroys the keys of that category only. That is right
 * for a request scoped to one category ("stop using my data for marketing") and wrong for a request to
 * be forgotten, where a category left readable is an erasure reported as done and not performed. The
 * whole-person erasure is {@link #eraseAllCategories(String, String, ErasureReason)}:
 * <pre>{@code
 * SubjectErasureReport report = context.eraseAllCategories("customer", customerId,
 *         ErasureReason.of("GDPR art.17 request #4711, approved by DPO " + today));
 *
 * report.categoriesErased();   // every category that held live keys for the person
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
 * A context whose store holds no keys cannot erase anything; calling this throws. The keys come from
 * {@code BoundedContextBuilder.shredding(...)}, or — when the builder is given none — from the codec
 * the {@code EventStorage} itself was built with ({@code EventStorage.shreddingCodec()}, what a
 * storage builder's {@code .shredding(...)} configures). Either is the same setup that lets an event
 * type declare a {@code Shreddable} component at all: without one, registering such a type fails at
 * startup rather than storing personal data in the clear.
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

	/**
	 * Erases a person's data across every category it was written under, by destroying every key held
	 * for the subject type and id whatever the category.
	 * <p>
	 * This is the erasure a request to be forgotten calls for. {@link #erase(DataSubject, ErasureReason)}
	 * destroys the keys of the one category its subject names, and a category is what a subject's data
	 * is <i>written</i> under — so which one a caller happens to name says nothing about the others,
	 * and erasing the {@code default} category of a customer who also holds {@code marketing} data
	 * leaves the marketing data readable while reporting success. This method takes no category, so it
	 * cannot be narrowed by accident, and answers one {@link ErasureReport} per category that held live
	 * keys.
	 * <p>
	 * Idempotent like {@link #erase}: a person holding no keys reports {@link SubjectErasureReport#isNoop()}.
	 *
	 * @param subjectType the kind of subject, as {@link DataSubject#type()} — {@code "customer"}
	 * @param subjectId   the pseudonymous id, as {@link DataSubject#id()} — never personal data itself
	 * @param reason      why, recorded alongside every destroyed key
	 * @return what was destroyed, per category
	 * @throws UnsupportedOperationException if this context's store holds no keys, or its key store
	 *                                       predates whole-person erasure
	 * @throws org.sliceworkz.eventstore.shredding.ShreddingException if the key store cannot be reached
	 * @throws IllegalArgumentException if an argument is null or blank
	 */
	SubjectErasureReport eraseAllCategories ( String subjectType, String subjectId, ErasureReason reason );

}
