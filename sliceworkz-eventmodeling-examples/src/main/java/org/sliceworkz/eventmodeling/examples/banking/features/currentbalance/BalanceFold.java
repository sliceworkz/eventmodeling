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
package org.sliceworkz.eventmodeling.examples.banking.features.currentbalance;

import java.math.BigDecimal;

import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthClosed;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MonthOpened;

/**
 * What one event does to one account's balance — the whole rule of this read model, in one pure
 * function of {@code (balance, event)}.
 *
 * <p><b>Why it is here rather than inside a read model.</b> The balance is projected twice: in the
 * background by {@link AccountBalancesReadModel}, and again by {@link CurrentBalanceReadModel} over
 * whatever has not reached that projection yet. Written out twice they would drift, and the symptom
 * would be an answer that depends on how far the projector had got — the one thing a seeded read
 * exists to make impossible. Written once they cannot.
 */
final class BalanceFold {

	private BalanceFold ( ) {
	}

	static BigDecimal apply ( BigDecimal balance, BankingEvent event ) {
		return switch ( event ) {
			case AccountOpened ignored -> BigDecimal.ZERO;
			case MoneyDeposited deposited -> balance.add(deposited.amount());
			case MoneyWithdrawn withdrawn -> balance.subtract(withdrawn.amount());
			// the carry-forward the previous month closed on: setting rather than adding, so folding it
			// on top of the transactions that produced it lands on the same number
			case MonthOpened opened -> opened.carryForwardBalance();
			// a summary of a month that has already been folded transaction by transaction
			case MonthClosed ignored -> balance;
		};
	}

	/** Which account an event concerns. Every event of this domain names one. */
	static AccountId accountOf ( BankingEvent event ) {
		return switch ( event ) {
			case AccountOpened opened -> opened.accountId();
			case MoneyDeposited deposited -> deposited.accountId();
			case MoneyWithdrawn withdrawn -> withdrawn.accountId();
			case MonthOpened opened -> opened.accountId();
			case MonthClosed closed -> closed.accountId();
		};
	}

}
