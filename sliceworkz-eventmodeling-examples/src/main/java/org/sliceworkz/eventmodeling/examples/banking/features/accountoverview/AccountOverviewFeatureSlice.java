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
package org.sliceworkz.eventmodeling.examples.banking.features.accountoverview;

import org.sliceworkz.eventmodeling.examples.banking.Banking;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * The account overview, as a feature slice: it appears in the context's inventory under its chapter,
 * but registers nothing itself.
 * <p>
 * The read model is eventually consistent, and a caller reads one by holding the instance being
 * projected — {@code read(...)} only ever constructs live models. The instance therefore belongs to
 * the application, which constructs it, registers it with
 * {@code builder.readmodel(overview).eventuallyConsistent()} and keeps the reference; see
 * {@code BankingExample}. Registering a fresh instance here would leave the application nothing to
 * read, and a static one shared through a field would be shared by every bounded context in the JVM.
 */
@FeatureSlice(type = Type.STATE_READ, context="banking", chapter="Account management", tags= {"batch"})
public class AccountOverviewFeatureSlice implements Slice<Banking> {

}
