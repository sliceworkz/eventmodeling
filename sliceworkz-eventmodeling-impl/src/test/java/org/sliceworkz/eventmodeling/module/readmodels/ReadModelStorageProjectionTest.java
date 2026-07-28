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
package org.sliceworkz.eventmodeling.module.readmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor.ProcessorMode;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;

/**
 * How a read model is projected follows from where it keeps its state, and from nothing else — this
 * is what makes "in-memory state, projected on one leader only" unconfigurable: it would leave every
 * other instance reading a model nobody filled.
 */
public class ReadModelStorageProjectionTest {

	@Test
	void ephemeralReadModelsAreProjectedOnEveryInstance ( ) {
		assertEquals(ProcessorMode.RUNNING_ON_ALL_INSTANCES, ReadModelModule.processorModeFor(ReadModelStorage.EPHEMERAL));
	}

	@Test
	void localReadModelsAreProjectedOnEveryInstance ( ) {
		assertEquals(ProcessorMode.RUNNING_ON_ALL_INSTANCES, ReadModelModule.processorModeFor(ReadModelStorage.LOCAL));
	}

	@Test
	void sharedReadModelsAreProjectedOnASingleLeader ( ) {
		assertEquals(ProcessorMode.RUNNING_ON_SINGLE_LEADER, ReadModelModule.processorModeFor(ReadModelStorage.SHARED));
	}

	@Test
	void onlySharedStorageIsWrittenOnce ( ) {
		assertEquals(true, ReadModelStorage.EPHEMERAL.projectedOnEveryInstance());
		assertEquals(true, ReadModelStorage.LOCAL.projectedOnEveryInstance());
		assertEquals(false, ReadModelStorage.SHARED.projectedOnEveryInstance());
	}

}
