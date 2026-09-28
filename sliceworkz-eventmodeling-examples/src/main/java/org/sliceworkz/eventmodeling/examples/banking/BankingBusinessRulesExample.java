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

import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.LARGE_WITHDRAWAL_JUSTIFIED;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.NO_OVERDRAFT;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.ORIGIN_OF_FUNDS_EXPLAINED;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.features.currentperiod.CurrentPeriodReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.deposit.DepositCommand;
import org.sliceworkz.eventmodeling.examples.banking.features.justifywithdrawal.JustifyWithdrawalCommand;
import org.sliceworkz.eventmodeling.examples.banking.features.justifywithdrawal.OverridesAwaitingJustificationReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.justifywithdrawal.OverridesAwaitingJustificationReadModel.AwaitingJustification;
import org.sliceworkz.eventmodeling.examples.banking.features.openbankaccount.OpenBankAccountCommand;
import org.sliceworkz.eventmodeling.examples.banking.features.withdraw.WithdrawCommand;
import org.sliceworkz.eventmodeling.rules.Evaluation;
import org.sliceworkz.eventmodeling.rules.Overrides;
import org.sliceworkz.eventmodeling.rules.RuleJudgement;
import org.sliceworkz.eventmodeling.rules.RuleViolationException;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * Business rules with SBVR enforcement levels, end to end: what a teller sees before submitting, what they
 * may override, what gets recorded, and how the rules enforced after the fact are followed up.
 *
 * <h2>What this example shows</h2>
 * <ol>
 *   <li><b>Evaluate, then execute.</b> A front end previews a command with {@code evaluate(...)} — nothing is
 *       appended — and renders each violated rule by its verdict: an error, an override checkbox, a checkbox
 *       with a text field, or a hint. Submitting is executing the same command with the ticked overrides.</li>
 *   <li><b>Override with explanation</b> — a large deposit, accepted once the origin of the funds is explained.</li>
 *   <li><b>Pre-authorized override</b> — an overdraft, refused to an anonymous caller, granted by an identified
 *       teller, and refused again once that teller has made three exceptions today. The command decides, on its
 *       own decision model, inside its consistency boundary.</li>
 *   <li><b>Strictly enforced</b> — a withdrawal above the maximum, blocked whatever is ticked.</li>
 *   <li><b>Post-justified override</b> — a large withdrawal, paid out on the spot, listed as awaiting its
 *       justification, and taken off the list by justifying it.</li>
 *   <li><b>Deferred enforcement</b> — a deposit taking the balance above the guarantee goes through, and an
 *       automation reports it to the customer afterwards.</li>
 *   <li><b>Guideline</b> — a withdrawal without a description: advice, nothing more.</li>
 * </ol>
 *
 * <h2>Bound to HTTP</h2>
 * Previewing and submitting are two methods on one resource, carrying the same body — {@code QUERY} is safe
 * and idempotent, which is exactly what an evaluation is, and a rejected submit answers with the same shape a
 * preview does:
 * <pre>{@code
 * routes.query("/api/withdrawals", ctx -> ctx.header("Cache-Control", "no-store")
 *                                             .json(banking.evaluate(toCommand(ctx), tracingOf(ctx))));
 * routes.post ("/api/withdrawals", ctx -> { banking.execute(toCommand(ctx), tracingOf(ctx)); ctx.status(201); });
 * app.exception(RuleViolationException.class, (e, ctx) -> ctx.status(422).json(e.evaluation()));
 *
 * // the body of both, with the overrides the teller ticked:
 * // { "accountId": "...", "amount": 250, "description": "rent",
 * //   "overrides": [ { "rule": "no-overdraft" } ] }
 * }</pre>
 * {@code Cache-Control: no-store} because an evaluation depends on the current state and on who is asking.
 */
public class BankingBusinessRulesExample {

	public static void main ( String[] args ) {

		EventStorage eventStorage = InMemoryEventStorage.newBuilder().build();

		ClosingTheBooks bank = BoundedContext.newBuilder(ClosingTheBooks.class)
			.name("banking-rules")
			.eventStorage(eventStorage)
			.instance(InstanceFactory.determine("banking-business-rules"))
			.readmodel(CurrentPeriodReadModel.class).live()
			.features()
				.rootPackage(BankingBusinessRulesExample.class.getPackage())
				.done()
			.build();
		bank.start();

		Tracing alice = Tracing.actorAndChannel("alice", "counter");
		LocalDate today = LocalDate.now(ZoneOffset.UTC);

		AccountId account = bank.execute(new OpenBankAccountCommand(
			BankingDomainWithClosingTheBooks.CUSTOMER.newId(), YearMonth.now(ZoneOffset.UTC))).response();

		// ── 1. Override with explanation: a large deposit ─────────────────────────────────

		step("1. A deposit of 12.000: the origin of the funds has to be explained");

		DepositCommand unexplained = new DepositCommand(account, new BigDecimal("12000"), "Car sold", Overrides.none());
		show(bank.evaluate(unexplained, alice));
		submit(bank, unexplained, alice);

		DepositCommand explained = new DepositCommand(account, new BigDecimal("12000"), "Car sold",
			Overrides.none().with(ORIGIN_OF_FUNDS_EXPLAINED, "Proceeds of selling a car, invoice on file"));
		submit(bank, explained, alice);
		balance(bank, account);

		// ── 2. Pre-authorized override: an overdraft ──────────────────────────────────────

		step("2. A withdrawal of 12.500 overdraws the account: who may grant that?");

		WithdrawCommand overdraft = new WithdrawCommand(account, new BigDecimal("12500"), "Kitchen", today, Overrides.of(NO_OVERDRAFT.id()));
		System.out.println("An anonymous caller:");
		show(bank.evaluate(overdraft));
		System.out.println("Teller alice, before ticking the box:");
		show(bank.evaluate(new WithdrawCommand(account, new BigDecimal("12500"), "Kitchen", today, Overrides.none()), alice));

		// still a large withdrawal too, so it needs the post-justified override as well
		WithdrawCommand overdraftAndLarge = new WithdrawCommand(account, new BigDecimal("12500"), "Kitchen", today,
			Overrides.of(NO_OVERDRAFT.id(), LARGE_WITHDRAWAL_JUSTIFIED.id()));
		submit(bank, overdraftAndLarge, alice);
		balance(bank, account);

		step("   Alice grants two more small overdrafts; the fourth of the day is refused");
		for ( int i = 2; i <= 4; i++ ) {
			System.out.println("Overdraft exception #" + i + ":");
			submit(bank, new WithdrawCommand(account, new BigDecimal("10"), "Coffee", today, Overrides.of(NO_OVERDRAFT.id())), alice);
		}

		// ── 3. Strictly enforced: above the maximum ───────────────────────────────────────

		step("3. A withdrawal of 60.000: strictly enforced maximum, nothing to tick");
		show(bank.evaluate(new WithdrawCommand(account, new BigDecimal("60000"), "Boat", today, Overrides.of("maximum-withdrawal")), alice));

		// ── 4. Deferred enforcement: over the guarantee ───────────────────────────────────

		step("4. A deposit of 9.000 on top of 95.000 takes the balance over the guarantee: allowed, reported later");
		submit(bank, new DepositCommand(account, new BigDecimal("95000"), "Inheritance",
			Overrides.none().with(ORIGIN_OF_FUNDS_EXPLAINED, "Inheritance, notary deed on file")), alice);
		DepositCommand overGuarantee = new DepositCommand(account, new BigDecimal("9000"), "Bonus");
		show(bank.evaluate(overGuarantee, alice));
		submit(bank, overGuarantee, alice);
		System.out.println("(ReportExcessBalanceAutomation picks the deferred enforcement up in the background)");

		// ── 5. Post-justified override: the follow-up ─────────────────────────────────────

		step("5. The large withdrawal from step 2 still has to be justified");
		for ( AwaitingJustification awaiting : bank.read(OverridesAwaitingJustificationReadModel.class).awaitingJustification() ) {
			System.out.println("Awaiting justification: " + awaiting);
			bank.execute(new JustifyWithdrawalCommand(awaiting.accountId(), awaiting.withdrawal(),
				"Customer renovating, confirmed by phone"), alice);
		}
		System.out.println("Still awaiting after justifying: "
			+ bank.read(OverridesAwaitingJustificationReadModel.class).awaitingJustification().size());

		// ── 6. Guideline: no description ──────────────────────────────────────────────────

		step("6. A withdrawal without description: a guideline, advice only");
		WithdrawCommand undescribed = new WithdrawCommand(account, new BigDecimal("20"), "", today, Overrides.none());
		show(bank.evaluate(undescribed, alice));
		submit(bank, undescribed, alice);

		bank.terminate();
		eventStorage.close();
	}

	private static void submit ( ClosingTheBooks bank, Command<BankingEvent> command, Tracing tracing ) {
		try {
			bank.execute(command, tracing);
			System.out.println("  -> executed");
		} catch ( RuleViolationException rejected ) {
			// what an HTTP binding answers with a 422: the same evaluation a preview returns
			System.out.println("  -> rejected (422):");
			show(rejected.evaluation());
		}
	}

	/** Renders an evaluation the way a front end would lay it out. */
	private static void show ( Evaluation evaluation ) {
		System.out.println("  evaluation: " + evaluation.outcome() + ( evaluation.rejection() != null ? ", " + evaluation.rejection() : "" ));
		for ( RuleJudgement judgement : evaluation.judgements() ) {
			String widget = switch ( judgement.verdict() ) {
				case BLOCKS -> "[error]   ";
				case OVERRIDE_REQUIRED -> judgement.explanationRequired() ? "[ ] + why " : "[ ]       ";
				case OVERRIDDEN -> "[x]       ";
				case DEFERRED -> "[later]   ";
				case ADVISED -> "[hint]    ";
			};
			System.out.println("    " + widget + judgement.statement() + ": " + judgement.message()
				+ ( judgement.reason() != null ? " (" + judgement.reason() + ")" : "" ));
		}
	}

	private static void balance ( ClosingTheBooks bank, AccountId account ) {
		bank.read(CurrentPeriodReadModel.class, account).getCurrentPeriod()
			.ifPresent(period -> System.out.println("  balance: " + period.balance()));
	}

	private static void step ( String title ) {
		System.out.println();
		System.out.println("=== " + title + " ===");
	}

}
