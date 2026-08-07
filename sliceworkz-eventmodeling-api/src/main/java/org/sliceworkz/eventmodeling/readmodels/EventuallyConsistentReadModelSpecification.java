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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;

/**
 * Registration of a read model given as an instance, which the framework projects in the background
 * and which can therefore only be eventually consistent.
 *
 * <p>The instance is what makes it so: one object, living as long as the bounded context, fed by a
 * processor of its own — where a read model registered by its class is built afresh for each read and
 * is projected then. Hence the two registrations being different methods rather than a flag.
 *
 * <p>Whether it is projected on every instance of the deployment or on a single elected leader is not
 * configured here either: it follows from the read model's own
 * {@link ReadModelWithMetaData#storage()}.
 *
 * <p><b>{@link #eventuallyConsistent()} has to be called.</b> {@code readmodel(...)} on its own
 * registers the read model but says nothing about how it is projected, and {@code build()} rejects
 * that rather than picking silently.
 *
 * @param <C> the bounded context type
 */
public interface EventuallyConsistentReadModelSpecification<C extends BoundedContext<?,?,?>> {

	/**
	 * Not available for a read model registered as an instance.
	 * <p>
	 * A live model is built per read, with that read's parameters, so it is registered as a class —
	 * {@code builder.readmodel(MyReadModel.class).live()}.
	 *
	 * @throws IllegalArgumentException always
	 */
	BoundedContextBuilder<C> live();

	/**
	 * Projects this read model <b>in the background</b>, on a processor of its own, so that reading it
	 * costs nothing but looking at the state it holds.
	 *
	 * <p>What that state costs to keep, and how far behind it may be, follows from
	 * {@link ReadModelWithMetaData#storage()}: {@code EPHEMERAL} is rebuilt from the stream at every
	 * process start (which {@code start()} waits for), {@code LOCAL} and {@code SHARED} resume from
	 * their bookmark. Either way the answer lags the stream by up to a poll interval.
	 *
	 * <p>Take this when a read spans many entities, or when a live model's replay cannot be bounded.
	 * {@link PublishingReadModel} is the base to extend for the in-memory case;
	 * {@code SqlReadModelProjector} for the durable one. Where that lag is not acceptable for a
	 * particular read, keep this projection and add a {@link SeededReadModel} on top of it rather than
	 * going back to a live model.
	 *
	 * <p><b>Saying it is required</b>, and {@code build()} names the read model that did not — see
	 * {@link LiveModelSpecification#live()} for why a mode that follows silently from the overload is
	 * not good enough.
	 */
	BoundedContextBuilder<C> eventuallyConsistent();

}
