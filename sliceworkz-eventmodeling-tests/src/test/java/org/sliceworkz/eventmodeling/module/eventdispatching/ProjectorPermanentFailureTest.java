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
package org.sliceworkz.eventmodeling.module.eventdispatching;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.readmodels.StaleLeadershipException;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventSerializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.IdempotencyKeyConflictException;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * The closed list of causes a projector retires on rather than retries. Pinned as a list because it
 * is a closed one: a cause missing from it is retried forever with a climbing
 * {@code consecutiveFailedRuns}, and a possibly-transient cause wrongly on it is a read model that
 * never comes back from an outage without an operator.
 */
public class ProjectorPermanentFailureTest {

	@Test
	void theCausesRetryingCannotHelpRetireTheProcessor ( ) {
		assertTrue(ProjectorProcessor.isPermanentFailure(new EventDeserializationException(EventType.named("Unreadable"), "poison")));
		assertTrue(ProjectorProcessor.isPermanentFailure(new EventSerializationException(EventType.named("Unwritable"), "unwritable", null)));
		assertTrue(ProjectorProcessor.isPermanentFailure(new EventStorageClosedException("closed")));
		assertTrue(ProjectorProcessor.isPermanentFailure(new StaleLeadershipException("reader", 1, 2)));
		// a batch mixing stored and new idempotency keys is refused whole, and refused identically on
		// every attempt: the eventstore extends it from RuntimeException, not EventStorageException,
		// because it is never worth retrying
		assertTrue(ProjectorProcessor.isPermanentFailure(
				new IdempotencyKeyConflictException(EventStreamId.forContext("ctx"), Set.of("k/1"), Set.of("k/2"))));
	}

	@Test
	void everythingElseIsRetried ( ) {
		// the possibly-transient kind, which EventStorageClosedException extends and is checked apart from
		assertFalse(ProjectorProcessor.isPermanentFailure(new EventStorageException("connection reset")));
		assertFalse(ProjectorProcessor.isPermanentFailure(new OptimisticLockingException(EventFilter.matchAll(), java.util.Optional.empty())));
		assertFalse(ProjectorProcessor.isPermanentFailure(new IllegalStateException("whatever the projection threw")));
	}
}
