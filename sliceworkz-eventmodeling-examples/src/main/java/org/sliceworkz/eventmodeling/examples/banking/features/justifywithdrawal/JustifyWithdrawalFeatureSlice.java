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
package org.sliceworkz.eventmodeling.examples.banking.features.justifywithdrawal;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.banking.ClosingTheBooks;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * The follow-up of the post-justified override on large withdrawals: a list of what still has to be
 * justified, and the command that justifies it. See {@code BUSINESS-RULES.md}, "Following up after the fact".
 */
@FeatureSlice(chapter = "Rulebook", tags = {"business-rules"})
public class JustifyWithdrawalFeatureSlice implements Slice<ClosingTheBooks> {

	@Override
	public void configureCommand ( BoundedContextBuilder<ClosingTheBooks> builder ) {
		builder.command(JustifyWithdrawalCommand.class);
	}

	@Override
	public void configureQuery ( BoundedContextBuilder<ClosingTheBooks> builder ) {
		builder.readmodel(OverridesAwaitingJustificationReadModel.class).live();
	}

}
