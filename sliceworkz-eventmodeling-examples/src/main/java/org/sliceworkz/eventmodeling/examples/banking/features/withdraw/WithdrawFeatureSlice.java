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
package org.sliceworkz.eventmodeling.examples.banking.features.withdraw;

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.LARGE_WITHDRAWAL_JUSTIFIED;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.MAXIMUM_WITHDRAWAL;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.NO_OVERDRAFT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.WITHDRAWAL_DESCRIBED;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.banking.ClosingTheBooks;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;
import org.sliceworkz.eventmodeling.slices.Slice;

@FeatureSlice(type = Type.STATE_CHANGE, context = "banking", chapter = "Transactions")
public class WithdrawFeatureSlice implements Slice<ClosingTheBooks> {

	@Override
	public void configureCommand(BoundedContextBuilder<ClosingTheBooks> builder) {
		builder.command(WithdrawCommand.class);
		// the rules the command checks, announced with the rulebook so an auditor sees them with their statement and level
		builder.businessRules(MAXIMUM_WITHDRAWAL, NO_OVERDRAFT, LARGE_WITHDRAWAL_JUSTIFIED, WITHDRAWAL_DESCRIBED);
	}

}
