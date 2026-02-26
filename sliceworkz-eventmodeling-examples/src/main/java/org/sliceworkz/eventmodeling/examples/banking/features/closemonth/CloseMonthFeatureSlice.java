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
package org.sliceworkz.eventmodeling.examples.banking.features.closemonth;

import org.sliceworkz.eventmodeling.examples.banking.Banking;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * Feature slice for the "Closing The Books" pattern.
 * <p>
 * This is both a STATE_CHANGE (the CloseMonthCommand) and an AUTOMATION
 * (the MonthEndClosingAutomation). Using OTHER here since the feature
 * combines multiple pattern types.
 * <p>
 * Components:
 * <ul>
 *   <li>{@link CloseMonthCommand} — closes current month, opens next</li>
 *   <li>{@link AccountsToCloseTodoList} — identifies accounts needing closing</li>
 *   <li>{@link MonthEndClosingAutomation} — processes the todo list</li>
 * </ul>
 */
@FeatureSlice(type = Type.AUTOMATION, context = "banking", chapter = "Closing The Books",
	tags = {"closing-the-books", "month-end"})
public class CloseMonthFeatureSlice implements Slice<Banking> {
}
