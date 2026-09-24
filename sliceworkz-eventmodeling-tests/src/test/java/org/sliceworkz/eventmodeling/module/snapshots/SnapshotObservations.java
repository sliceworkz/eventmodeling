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
package org.sliceworkz.eventmodeling.module.snapshots;

import java.util.List;

import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage.MissReason;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver;
import org.sliceworkz.eventmodeling.testing.RecordingBoundedContextObserver.Recording;

/**
 * Counts what a recording observer was told about snapshot storage calls — the questions the snapshot
 * meters used to answer, asked of the observations that replaced them.
 */
public final class SnapshotObservations {

	private final RecordingBoundedContextObserver observer;

	public SnapshotObservations ( RecordingBoundedContextObserver observer ) {
		this.observer = observer;
	}

	/** Loads that found a snapshot under the given version. */
	public long found ( String version ) {
		return loads().stream()
				.filter(r -> r.observation(Observation.SnapshotLoad.class).version().equals(version))
				.filter(r -> r.outcome().orElse(null) instanceof Outcome.SnapshotFound)
				.count();
	}

	/** Loads that missed for the given reason, asked under the given version. */
	public long missed ( MissReason reason, String version ) {
		return loads().stream()
				.filter(r -> r.observation(Observation.SnapshotLoad.class).version().equals(version))
				.filter(r -> r.outcome().orElse(null) instanceof Outcome.SnapshotMissed missed && missed.reason() == reason)
				.count();
	}

	/** Saves that completed under the given version. */
	public long written ( String version ) {
		return saves().stream()
				.filter(r -> r.observation(Observation.SnapshotSave.class).version().equals(version))
				.filter(r -> r.outcome().isPresent())
				.count();
	}

	/** Every load call, whatever it answered. */
	public List<Recording> loads ( ) {
		return observer.recordings(Observation.SnapshotLoad.class);
	}

	/** Every save call, whatever it answered. */
	public List<Recording> saves ( ) {
		return observer.recordings(Observation.SnapshotSave.class);
	}

	/** Calls of the given kind that failed. */
	public long failed ( List<Recording> calls ) {
		return calls.stream().filter(r -> r.failure().isPresent()).count();
	}

}
