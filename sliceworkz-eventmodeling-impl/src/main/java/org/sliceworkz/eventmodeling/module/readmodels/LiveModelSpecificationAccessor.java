/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.module.readmodels;

import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;

/**
 * Internal accessor interface for live model specification data needed by ReadModelModule.
 */
public interface LiveModelSpecificationAccessor<DOMAIN_EVENT_TYPE> {

	Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass ( );

	SnapshotStorage<Object> snapshotStorage ( );

	boolean readSnapshots ( );

	boolean writeSnapshots ( );

	int snapshotEventCountThreshold ( );

}
