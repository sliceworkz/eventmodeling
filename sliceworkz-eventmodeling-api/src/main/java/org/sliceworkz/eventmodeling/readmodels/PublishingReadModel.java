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
package org.sliceworkz.eventmodeling.readmodels;

import java.util.Optional;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;

/**
 * An in-memory eventually consistent read model that folds events into an immutable state and
 * publishes that state together with the position it reflects, atomically, once per batch.
 *
 * <p><b>Two problems this solves, and they are the same problem.</b> An ordinary read model mutates
 * its own fields from {@code when}, on the projector's thread, while readers call its getters from
 * theirs — so a reader can observe a half-applied batch, and there is nothing in the class saying
 * otherwise. And a reader that wants to catch the model up itself (see {@link SeededReadModel}) needs
 * the state <em>and</em> how far it has come as one observation; taking them separately re-applies or
 * skips whatever the projector committed in between. Publishing an immutable
 * {@link ReadModelResult} — the state and the position it reflects — through a single volatile field
 * answers both at once: a reader takes one reference and holds a consistent, private, unchanging view
 * of the model.
 *
 * <p><b>What a subclass writes.</b> A state type, a starting value, and a fold:
 *
 * <pre>{@code
 * public final class AccountOverview extends PublishingReadModel<BankingEvent, Map<String,Balance>> {
 *
 *     protected Map<String,Balance> initialState ( ) { return Map.of(); }
 *
 *     protected Map<String,Balance> apply ( Map<String,Balance> state, Event<BankingEvent> event ) {
 *         return BalanceFold.apply(state, event);        // pure, and reusable by a seeded read
 *     }
 *
 *     public EventQuery eventQuery ( ) { return EventQuery.forEvents(EventTypesFilter.any(), Tags.none()); }
 *
 *     public Balance of ( String accountId ) { return state().getOrDefault(accountId, Balance.ZERO); }
 * }
 * }</pre>
 *
 * <p><b>The state must be immutable, or at least never mutated after it is returned from
 * {@link #apply}.</b> This is the whole basis of the guarantee: a published state that a later batch
 * mutates in place is being read by whoever still holds it. Persistent or copy-on-write collections,
 * or records of them, are what belong here. The cost is a copy per batch rather than per event, which
 * is what makes it affordable; where the model is large enough that even that is too much, this class
 * is the wrong tool — guard a mutable model with a lock and copy out only the answer plus
 * {@link #upTo()}.
 *
 * <p><b>Storage is {@link ReadModelStorage#EPHEMERAL}</b> and deliberately not overridable: the state
 * is in heap, so every instance of the bounded context projects its own copy, and a stale bookmark is
 * dropped at startup. A durable read model records its position in its own store instead — see
 * {@link SelfBookmarkingProjection} and {@code SqlReadModelProjector}.
 *
 * @param <DOMAIN_EVENT_TYPE> the domain event type this read model is projected from
 * @param <STATE> the immutable state this read model folds its events into
 */
public abstract class PublishingReadModel<DOMAIN_EVENT_TYPE, STATE>
		implements ReadModelWithMetaData<DOMAIN_EVENT_TYPE>, BatchAwareProjection<DOMAIN_EVENT_TYPE> {

	// the only field a reader touches, and it is only ever replaced, never mutated
	private volatile ReadModelResult<STATE> published;

	// the batch in progress, on the projector's thread alone. Not volatile on purpose: it is not the
	// reader's business, and it becomes visible to one only by being published above
	private STATE pending;

	/**
	 * The state a read model that has projected nothing starts from. Must not return null.
	 * <p>
	 * Called lazily rather than from a constructor, so that a subclass may build it out of its own
	 * fields without depending on whether those have been assigned yet.
	 */
	protected abstract STATE initialState ( );

	/**
	 * Folds one event into the state and returns the result, leaving the argument untouched.
	 * <p>
	 * This is the one place the read model's rules live, and keeping it a pure function of
	 * {@code (state, event)} is what lets a {@link SeededReadModel} reuse it to catch a base up
	 * without stating those rules a second time.
	 */
	protected abstract STATE apply ( STATE state, Event<DOMAIN_EVENT_TYPE> event );

	/**
	 * The current state together with the reference it reflects — one volatile read, so the two
	 * cannot disagree however far the projector has got in the meantime.
	 * <p>
	 * This is what a {@link SeededReadModel} over this model calls from its {@code seed()}.
	 */
	public final ReadModelResult<STATE> published ( ) {
		ReadModelResult<STATE> current = published;
		if ( current == null ) {
			synchronized ( this ) {
				if ( published == null ) {
					STATE initial = initialState();
					if ( initial == null ) {
						throw new IllegalStateException("initialState() of " + readmodelName() + " returned null");
					}
					published = new ReadModelResult<>(initial, null);
				}
				current = published;
			}
		}
		return current;
	}

	/** The current state. Equivalent to {@code published().data()}. */
	public final STATE state ( ) {
		return published().data();
	}

	/**
	 * The reference of the last event folded into the current state, or null when none has been.
	 * Equivalent to {@code published().upTo()}.
	 */
	public final EventReference upTo ( ) {
		return published().upTo();
	}

	@Override
	public final void beforeBatch ( ) {
		pending = published().data();
	}

	@Override
	public final void when ( Event<DOMAIN_EVENT_TYPE> event ) {
		if ( pending == null ) {
			// beforeBatch is documented to precede the first event of a batch, so this is defensive
			// only -- but folding onto null would corrupt the model rather than fail, so it is cheap
			// insurance against a projector that ever stops making that promise
			pending = published().data();
		}
		pending = apply(pending, event);
	}

	/**
	 * Publishes the batch: the state it produced and the reference of its last event become visible
	 * to readers in one step.
	 * <p>
	 * A batch that matched no event of ours leaves the position where it was — the projector read
	 * past events this read model does not handle, which says nothing about state it does not hold.
	 */
	@Override
	public final void afterBatch ( Optional<EventReference> lastEventReference ) {
		STATE state = pending != null ? pending : published().data();
		published = new ReadModelResult<>(state, lastEventReference.orElse(published().upTo()));
		pending = null;
	}

	@Override
	public final void cancelBatch ( ) {
		// the batch never happened: what was published stays published, and the next batch starts
		// from it again
		pending = null;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Always {@link ReadModelStorage#EPHEMERAL}, and final: the state of this class lives in heap.
	 */
	@Override
	public final ReadModelStorage storage ( ) {
		return ReadModelStorage.EPHEMERAL;
	}

}
