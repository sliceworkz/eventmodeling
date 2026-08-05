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
package org.sliceworkz.eventmodeling.module.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Every bookmark placed on a stream is announced to every subscriber of that stream, so an automation is
 * told about readers that have nothing to do with it: an application's own {@code Projector}, a migration
 * tool, an operator's script. Those readers are named by whoever wrote them and are under no obligation to
 * look like a {@code ProcessorIdentification}.
 * <p>
 * The processor used to parse the reader name before comparing it, which threw
 * {@code IllegalArgumentException} on every one of them. The event store contains a listener failure, so
 * nothing broke — it logged at ERROR with a stack trace instead, once per bookmark placement per
 * automation, for a notification that was never this automation's business.
 */
public class AutomationForeignBookmarkTest {

	private static final String MONITORED = "mock/readmodel/TodoList[shared]";

	@Test
	void ourOwnMonitoredProjectorMatches ( ) {
		assertTrue(AutomationProcessor.isMonitoredBookmark(MONITORED, MONITORED));
	}

	@Test
	void aForeignReaderIsIgnoredRatherThanThrownOn ( ) {
		// none of these parse as a ProcessorIdentification, and none of them are ours
		assertFalse(AutomationProcessor.isMonitoredBookmark("someReader", MONITORED));
		assertFalse(AutomationProcessor.isMonitoredBookmark("migration-tool", MONITORED));
		assertFalse(AutomationProcessor.isMonitoredBookmark("", MONITORED));
		assertFalse(AutomationProcessor.isMonitoredBookmark(null, MONITORED));
		assertFalse(AutomationProcessor.isMonitoredBookmark("mock/readmodel/TodoList[unknown-storage]", MONITORED));
	}

	@Test
	void anotherProcessorOfOurOwnKindIsIgnoredToo ( ) {
		assertFalse(AutomationProcessor.isMonitoredBookmark("mock/readmodel/OtherReadModel[shared]", MONITORED));
		assertFalse(AutomationProcessor.isMonitoredBookmark("other/readmodel/TodoList[shared]", MONITORED));
		// same read model, different storage class: a real mismatch, and one the automation must not act on
		assertFalse(AutomationProcessor.isMonitoredBookmark("mock/readmodel/TodoList[ephemeral:logical#physical]", MONITORED));
	}

	@Test
	void aReadModelNamedWithTheSeparatorStillMatchesItself ( ) {
		// readmodelName() is free text, so a name carrying '/' or '[' produces a reader that toString/parse
		// does not round trip - comparing the strings is what keeps such a read model wakeable at all
		String awkward = "mock/readmodel/todo/list[v2][shared]";
		assertTrue(AutomationProcessor.isMonitoredBookmark(awkward, awkward));
	}
}
