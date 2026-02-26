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
package org.sliceworkz.eventmodeling.snapshots;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;

/**
 * Provides a fluent API for configuring snapshot behavior for a live model.
 *
 * @param <C> the bounded context type
 */
public interface LiveModelSnapshotSpecification<C extends BoundedContext<?,?,?>> {

	LiveModelSnapshotSpecification<C> eventCountThreshold ( int eventCountThreshold );

	BoundedContextBuilder<C> readAndWrite ( );

	BoundedContextBuilder<C> readOnly ( );

	BoundedContextBuilder<C> writeOnly ( );

}
