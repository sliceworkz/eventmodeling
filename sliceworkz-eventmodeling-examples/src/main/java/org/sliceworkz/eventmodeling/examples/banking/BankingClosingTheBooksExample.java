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
package org.sliceworkz.eventmodeling.examples.banking;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.CustomerId;
import org.sliceworkz.eventmodeling.examples.banking.features.closemonth.CloseMonthCommand;
import org.sliceworkz.eventmodeling.examples.banking.features.currentperiod.ActiveMonthReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.currentbalance.AccountBalancesReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.currentbalance.CurrentBalanceReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.currentperiod.CurrentPeriodReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.deposit.DepositCommand;
import org.sliceworkz.eventmodeling.examples.banking.features.monthstatement.MonthStatementReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.monthstatement.MonthStatementReadModel.MonthStatement;
import org.sliceworkz.eventmodeling.examples.banking.features.openbankaccount.OpenBankAccountCommand;
import org.sliceworkz.eventmodeling.examples.banking.features.withdraw.WithdrawCommand;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Demonstrates the "Closing The Books" pattern for bank account monthly periods.
 *
 * <h2>What this example shows</h2>
 * <ol>
 *   <li>Opening an account starts the first monthly period</li>
 *   <li>Deposits and withdrawals happen within the active period</li>
 *   <li>Closing the books for a month produces a summary event and opens the next period</li>
 *   <li>The closing balance carries forward as the opening balance for the next period</li>
 *   <li>Closed month statements become immutable (efficient to query and cache)</li>
 *   <li>The new period starts fresh with only the carry-forward balance</li>
 * </ol>
 *
 * <h2>Event lifecycle</h2>
 * <pre>
 *   AccountOpened → MoneyDeposited* → MoneyWithdrawn* → MonthClosed → MonthOpened → ...
 * </pre>
 *
 * <h2>Tag rotation</h2>
 * <p>Each period's events are tagged with both the account identity and the month.
 * When the books are closed, the {@code MonthClosed} event is tagged with the OLD month,
 * and the {@code MonthOpened} event is tagged with the NEW month. This "tag rotation"
 * means querying a specific month's events only returns a small, bounded set.</p>
 */
public class BankingClosingTheBooksExample {

	public static void main(String[] args) {

		EventStorage eventStorage = InMemoryEventStorage.newBuilder().build();
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage);

		Instance instance = InstanceFactory.determine("banking-closing-the-books");

		/*
		 * Create the BoundedContext using the extended domain types that include
		 * period lifecycle events (MonthClosed, MonthOpened).
		 *
		 * Read models must be registered explicitly so the bounded context knows
		 * how to project them when bc.read() is called.
		 */
		var builder = BoundedContext.newBuilder(ClosingTheBooks.class)
			.name("banking-ctb")
			.eventStorage(eventStorage)
			.instance(instance);

		builder.readmodel(ActiveMonthReadModel.class).live();
		builder.readmodel(CurrentPeriodReadModel.class).live();
		builder.readmodel(MonthStatementReadModel.class).live();

		/*
		 * The seeded read: an eventually consistent projection of every account's balance, plus a
		 * read model that starts from it and projects only what has not reached it yet. The base is
		 * registered like any other eventually consistent read model; the read that catches it up is
		 * registered like any other live model. See step 3b.
		 */
		AccountBalancesReadModel balances = new AccountBalancesReadModel();
		builder.readmodel(balances).eventuallyConsistent();
		builder.readmodel(CurrentBalanceReadModel.class).live();

		builder.features()
			.rootPackage(BankingClosingTheBooksExample.class.getPackage())
			.done();

		ClosingTheBooks bc = builder.build();
		
		bc.start();

		EventStream<BankingEvent> eventStream = eventStore.getEventStream(
			EventStreamId.forContext("banking-ctb").withPurpose("domain"),
			BankingEvent.class);


		// ── Step 1: Open an account (starts January 2025) ───────────────

		System.out.println("=== STEP 1: Open account (starts January 2025 period) ===");
		System.out.println();

		YearMonth january = YearMonth.of(2025, 1);
		CustomerId customerId = BankingDomainWithClosingTheBooks.CUSTOMER.newId();

		Optional<EventReference> ref = bc.execute(new OpenBankAccountCommand(customerId, january));

		// Retrieve the AccountOpened event to get the generated accountId
		AccountOpened accountOpened = eventStream.query(EventQuery.forEvents(EventTypesFilter.of(AccountOpened.class), Tags.none()))
			.filter(e -> e.reference().id().equals(ref.get().id()))
			.map(Event::data)
			.map(e -> (AccountOpened) e)
			.findFirst()
			.get();
		AccountId accountId = accountOpened.accountId();

		System.out.println("Account opened: " + accountId.value());
		System.out.println("Initial period: " + accountOpened.initialMonth());
		System.out.println();


		// ── Step 2: Make transactions in January ────────────────────────
		//
		// Commands no longer need the active month passed in — the decision model
		// discovers it automatically via initQuery (a single backwards query).

		System.out.println("=== STEP 2: Transactions in January 2025 ===");
		System.out.println();

		bc.execute(new DepositCommand(accountId, new BigDecimal("1000.00"), "Initial deposit"));
		bc.execute(new DepositCommand(accountId, new BigDecimal("2500.00"), "Salary"));
		bc.execute(new WithdrawCommand(accountId, new BigDecimal("150.00"), "Groceries"));
		bc.execute(new WithdrawCommand(accountId, new BigDecimal("85.00"), "Utilities"));

		System.out.println("Deposited 1000.00 (Initial deposit)");
		System.out.println("Deposited 2500.00 (Salary)");
		System.out.println("Withdrew  150.00 (Groceries)");
		System.out.println("Withdrew  85.00 (Utilities)");
		System.out.println();


		// ── Step 3: View current period (January, still open) ───────────

		System.out.println("=== STEP 3: Current period state (January, open) ===");
		System.out.println();

		CurrentPeriodReadModel janPeriodModel = bc.read(CurrentPeriodReadModel.class, accountId);
		janPeriodModel.getCurrentPeriod().ifPresent(period -> {
			System.out.println("  Month:        " + period.month());
			System.out.println("  Balance:      " + period.balance());
			System.out.println("  Deposits:     " + period.deposits());
			System.out.println("  Withdrawals:  " + period.withdrawals());
			System.out.println("  Transactions: " + period.transactionCount());
			System.out.println("  Closed:       " + period.closed());
		});
		System.out.println();


		// ── Step 3b: The same balance, current, without replaying anything ──
		//
		// The four transactions above were appended a moment ago, so the background projection may or
		// may not have reached them yet -- deliberately not waited for here, because the answer must
		// not depend on it. Whatever it holds is the base, and the read projects the rest.

		System.out.println("=== STEP 3b: Current balance (seeded read) ===");
		System.out.println();

		CurrentBalanceReadModel currentBalance = bc.read(CurrentBalanceReadModel.class, balances, accountId);

		System.out.println("  Balance:              " + currentBalance.balance());
		System.out.println("  Background projection: " + balances.balanceOf(accountId) + " (as far as its thread has got)");
		System.out.println("  Events folded on read: " + currentBalance.foldedInRead() + " (the rest came from the base)");
		System.out.println("  Current as of:        " + currentBalance.upTo());
		System.out.println();


		// ── Step 4: View January statement (still open) ─────────────────

		System.out.println("=== STEP 4: January statement (before closing) ===");
		System.out.println();

		MonthStatementReadModel janStatementModel = bc.read(MonthStatementReadModel.class, accountId, january);
		janStatementModel.getStatement().ifPresent(BankingClosingTheBooksExample::printStatement);
		System.out.println();


		// ── Step 5: Close the books for January ─────────────────────────
		//
		// This is the core operation. It:
		// 1. Raises a MonthClosed event (tagged with January) containing the summary
		// 2. Raises a MonthOpened event (tagged with February) carrying the balance forward
		// Both events are appended atomically in a single command execution.

		System.out.println("=== STEP 5: Close the books for January 2025 ===");
		System.out.println();

		bc.execute(new CloseMonthCommand(accountId, january));

		System.out.println("Books closed for January 2025.");
		System.out.println();


		// ── Step 6: View the closed January statement ────────────────────
		//
		// After closing, the January statement is now immutable.
		// The MonthStatementReadModel queries only events tagged with
		// account + January — a small, bounded set. Perfect for caching.

		System.out.println("=== STEP 6: January statement (after closing - now immutable) ===");
		System.out.println();

		MonthStatementReadModel closedJanModel = bc.read(MonthStatementReadModel.class, accountId, january);
		closedJanModel.getStatement().ifPresent(BankingClosingTheBooksExample::printStatement);
		System.out.println();


		// ── Step 7: View current period (should be February) ────────────
		//
		// The closing balance from January ($3265.00) should carry forward
		// as the opening balance for February. Period counters are reset.

		System.out.println("=== STEP 7: Current period after closing (February 2025) ===");
		System.out.println();

		// Check the active month — should now be February
		ActiveMonthReadModel postCloseMonth = bc.read(ActiveMonthReadModel.class, accountId);
		System.out.println("Active month after closing: " + postCloseMonth.activeMonth());

		CurrentPeriodReadModel febPeriodModel = bc.read(CurrentPeriodReadModel.class, accountId);
		febPeriodModel.getCurrentPeriod().ifPresent(period -> {
			System.out.println("  Month:             " + period.month());
			System.out.println("  Balance:           " + period.balance());
			System.out.println("  Deposits:          " + period.deposits());
			System.out.println("  Withdrawals:       " + period.withdrawals());
			System.out.println("  Transactions:      " + period.transactionCount());
			System.out.println("  Closed:            " + period.closed());
			System.out.println("  (Balance carried forward from January)");
		});
		System.out.println();


		// ── Step 8: Make transactions in February ───────────────────────

		System.out.println("=== STEP 8: Transactions in February 2025 ===");
		System.out.println();

		bc.execute(new DepositCommand(accountId, new BigDecimal("2500.00"), "February salary"));
		bc.execute(new WithdrawCommand(accountId, new BigDecimal("1200.00"), "Rent"));
		bc.execute(new WithdrawCommand(accountId, new BigDecimal("60.00"), "Streaming services"));

		System.out.println("Deposited 2500.00 (February salary)");
		System.out.println("Withdrew  1200.00 (Rent)");
		System.out.println("Withdrew  60.00 (Streaming services)");
		System.out.println();


		// ── Step 9: View current period (February with transactions) ────

		System.out.println("=== STEP 9: Current period state (February, open) ===");
		System.out.println();

		CurrentPeriodReadModel febUpdatedModel = bc.read(CurrentPeriodReadModel.class, accountId);
		febUpdatedModel.getCurrentPeriod().ifPresent(period -> {
			System.out.println("  Month:        " + period.month());
			System.out.println("  Balance:      " + period.balance());
			System.out.println("  Deposits:     " + period.deposits());
			System.out.println("  Withdrawals:  " + period.withdrawals());
			System.out.println("  Transactions: " + period.transactionCount());
			System.out.println("  Closed:       " + period.closed());
		});
		System.out.println();


		// ── Step 10: View February statement (still open) ───────────────

		System.out.println("=== STEP 10: February statement (still open) ===");
		System.out.println();

		YearMonth february = YearMonth.of(2025, 2);
		MonthStatementReadModel febStatementModel = bc.read(MonthStatementReadModel.class, accountId, february);
		febStatementModel.getStatement().ifPresent(BankingClosingTheBooksExample::printStatement);
		System.out.println();

		/*
		 * Shut down from the outside in: the bounded context (which closes the EventStore it built for
		 * itself), then the store this example built to read the stream directly, then the storage that
		 * backs both -- it is ours, and nothing else closes it.
		 */
		bc.terminate();
		eventStore.close();
		eventStorage.close();
	}

	private static void printStatement(MonthStatement statement) {
		System.out.println("  Account:         " + statement.accountId());
		System.out.println("  Month:           " + statement.month());
		System.out.println("  Opening balance: " + statement.openingBalance());
		System.out.println("  Current balance: " + statement.currentBalance());
		System.out.println("  Closed:          " + statement.closed());
		System.out.println("  Transactions:");
		statement.transactions().forEach(tx ->
			System.out.println("    " + tx.type() + "  " + tx.amount() + "  " + tx.description()));
	}
}
