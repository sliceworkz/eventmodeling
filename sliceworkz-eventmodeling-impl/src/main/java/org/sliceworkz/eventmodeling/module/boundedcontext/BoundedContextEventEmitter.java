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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.slices.Slice;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Internal helper that turns a {@link BoundedContextEvent} into a tagged {@link EphemeralEvent} and
 * delivers it to the configured {@link BoundedContextListener}.
 * <p>
 * A single emitter is created per bounded context and shared by all kernel modules. When no listener
 * is registered (the {@link BoundedContextListener#NO_OP} sentinel) {@link #enabled()} returns
 * {@code false} and callers should skip building events entirely so there is no overhead on the hot
 * path.
 */
public final class BoundedContextEventEmitter {

	private final BoundedContextListener listener;
	private final Instance instance;
	private final SliceRegistry sliceRegistry;

	public BoundedContextEventEmitter ( BoundedContextListener listener, Instance instance, SliceRegistry sliceRegistry ) {
		this.listener = listener == null ? BoundedContextListener.NO_OP : listener;
		this.instance = instance;
		this.sliceRegistry = sliceRegistry;
	}

	/**
	 * @return {@code true} when a real listener is registered, {@code false} for the no-op sentinel.
	 */
	public boolean enabled ( ) {
		return listener != BoundedContextListener.NO_OP;
	}

	/**
	 * Resolves the feature slice owning the given component class (by package convention), or
	 * {@code null} when the component is not located within a known slice package.
	 */
	public BoundedContextEvent.FeatureSlice sliceFor ( Class<?> componentClass ) {
		return sliceRegistry.resolve(componentClass);
	}

	/**
	 * Describes a feature slice - deployed or not - for the lifecycle inventory, including the
	 * members it registered.
	 */
	public BoundedContextEvent.FeatureSlice describe ( Slice<?> slice ) {
		return sliceRegistry.describe(slice);
	}

	/**
	 * Wraps the given event with system ({@code kernel}) tracing tags and delivers it to the
	 * listener. Use this for events not triggered by a specific operation (e.g. lifecycle). Does
	 * nothing when no listener is registered.
	 */
	public void emit ( BoundedContextEvent event ) {
		emit(event, null);
	}

	/**
	 * Wraps the given event with tracing tags and delivers it to the listener. The {@code actor},
	 * {@code channel} and {@code command} are taken from the triggering operation's {@code tracing}
	 * so the event reflects who/what caused it (e.g. a web user versus an automation); the instance
	 * tags always come from this bounded context. When {@code tracing} is {@code null} (or carries no
	 * actor) the event falls back to the system actor. Does nothing when no listener is registered.
	 */
	public void emit ( BoundedContextEvent event, Tracing tracing ) {
		if ( enabled() ) {
			Tracing kernelTracing = ( tracing == null || tracing.actor() == null )
					? Tracing.kernel(instance)
					: Tracing.init(instance).actor(tracing.actor()).channel(tracing.channel()).command(tracing.command())
							.agent(tracing.agentId(), tracing.agentName());
			EphemeralEvent<BoundedContextEvent> ephemeralEvent =
					kernelTracing.storeOn(EphemeralEvent.of(event, Tags.none()));
			listener.on(ephemeralEvent);
		}
	}

}
