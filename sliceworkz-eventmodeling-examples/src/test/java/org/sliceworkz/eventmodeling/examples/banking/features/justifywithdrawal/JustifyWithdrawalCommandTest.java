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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.LARGE_WITHDRAWAL_JUSTIFIED;
import static org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.WITHDRAWAL_DESCRIBED;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.MoneyWithdrawn;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingEvent.RulebookFollowUp.WithdrawalJustified;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomainWithClosingTheBooks.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.rules.EnforcementLevel;
import org.sliceworkz.eventmodeling.rules.RuleFollowUp;
import org.sliceworkz.eventmodeling.rules.RuleTags;
import org.sliceworkz.eventmodeling.rules.RuleViolation;
import org.sliceworkz.eventmodeling.rules.RuleViolation.Disposition;
import org.sliceworkz.eventmodeling.testing.CommandTest;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The follow-up of a post-justified override: the list of withdrawals awaiting their justification, and the
 * command that takes one off it. The pending withdrawal is seeded as the kernel stores one — the violation in
 * the payload, the {@code x-rule-justification-pending} tag beside it — and referred to by its event id, which
 * is how the read model hands it out.
 */
public class JustifyWithdrawalCommandTest extends CommandTest<BankingEvent, BankingInboundEvent, BankingOutboundEvent> {

	private static final AccountId ACCOUNT_1 = BankingDomainWithClosingTheBooks.ACCOUNT.id("acc-1");
	private static final YearMonth JANUARY = YearMonth.of(2025, 1);

	@Override
	public Class<BankingEvent> domainEventType ( ) {
		return BankingEvent.class;
	}

	@Override
	public Class<BankingInboundEvent> inboundEventType ( ) {
		return BankingInboundEvent.class;
	}

	@Override
	public Class<BankingOutboundEvent> outboundEventType ( ) {
		return BankingOutboundEvent.class;
	}

	@Override
	public void configure ( BoundedContextBuilder<?> builder ) {
		builder.readmodel(OverridesAwaitingJustificationReadModel.class).live();
	}

	@Test
	void aJustifiedWithdrawalLeavesTheList ( ) {
		String withdrawal = seedPendingWithdrawal();
		assertEquals(List.of(withdrawal), awaiting());
		assertEquals("alice", kernel().read(OverridesAwaitingJustificationReadModel.class).awaitingJustification().get(0).teller());

		given()
			.as("alice")
			.when(new JustifyWithdrawalCommand(ACCOUNT_1, withdrawal, "customer renovating"))
			.then()
			.event(new WithdrawalJustified(ACCOUNT_1, withdrawal,
					new RuleFollowUp(LARGE_WITHDRAWAL_JUSTIFIED.id(), withdrawal, RuleFollowUp.Kind.JUSTIFIED, "customer renovating")),
				// the kernel pairs the justification with the override it settles
				Tags.of(RuleTags.justified(LARGE_WITHDRAWAL_JUSTIFIED, EventId.of(withdrawal))));

		assertEquals(List.of(), awaiting());
	}

	@Test
	void aWithdrawalIsJustifiedOnce ( ) {
		String withdrawal = seedPendingWithdrawal();
		given()
			.when(new JustifyWithdrawalCommand(ACCOUNT_1, withdrawal, "first"))
			.when(new JustifyWithdrawalCommand(ACCOUNT_1, withdrawal, "second"))
			.then()
			.businessError("Withdrawal " + withdrawal + " was already justified");
	}

	@Test
	void aWithdrawalThatNeedsNoJustificationCannotBeJustified ( ) {
		given()
			.event(accountOpened(), accountTags())
			// a withdrawal that did violate a rule — just not one that asks for a justification
			.event(new MoneyWithdrawn(ACCOUNT_1, JANUARY, new BigDecimal("10"), "", List.of(new RuleViolation(
				WITHDRAWAL_DESCRIBED.id(), EnforcementLevel.GUIDELINE, Disposition.GUIDELINE_NOT_FOLLOWED, "No description given", null))), accountTags());
		String withdrawal = lastEventId();

		given()
			.when(new JustifyWithdrawalCommand(ACCOUNT_1, withdrawal, "why not"))
			.then()
			.businessError("Withdrawal " + withdrawal + " needs no justification");
	}

	@Test
	void anUnknownWithdrawalCannotBeJustified ( ) {
		given()
			.event(accountOpened(), accountTags())
			.when(new JustifyWithdrawalCommand(ACCOUNT_1, "no-such-event", "why not"))
			.then()
			.businessError("No withdrawal no-such-event on this account");
	}

	@Test
	void aJustificationHasToSaySomething ( ) {
		assertThrows(IllegalArgumentException.class, () -> new JustifyWithdrawalCommand(ACCOUNT_1, "w", "  "));
	}

	private String seedPendingWithdrawal ( ) {
		given()
			.event(accountOpened(), accountTags())
			.as("alice")
			.event(new MoneyWithdrawn(ACCOUNT_1, JANUARY, new BigDecimal("6000"), "car", List.of(new RuleViolation(
					LARGE_WITHDRAWAL_JUSTIFIED.id(), EnforcementLevel.POST_JUSTIFIED_OVERRIDE, Disposition.JUSTIFICATION_PENDING, "large", null))),
				accountTags().merge(Tags.of(RuleTags.overridden(LARGE_WITHDRAWAL_JUSTIFIED), RuleTags.justificationPending(LARGE_WITHDRAWAL_JUSTIFIED))));
		return lastEventId();
	}

	private String lastEventId ( ) {
		return eventStore().getEventStream(eventStreamId(), BankingEvent.class).head().orElseThrow().id().value();
	}

	private List<String> awaiting ( ) {
		return kernel().read(OverridesAwaitingJustificationReadModel.class).awaitingJustification().stream()
			.map(OverridesAwaitingJustificationReadModel.AwaitingJustification::withdrawal).toList();
	}

	private static AccountOpened accountOpened ( ) {
		return new AccountOpened(ACCOUNT_1, BankingDomainWithClosingTheBooks.CUSTOMER.id("cust-1"), JANUARY, LocalDate.of(2025, 1, 1));
	}

	private static Tags accountTags ( ) {
		return BankingDomainWithClosingTheBooks.ACCOUNT.tags(ACCOUNT_1);
	}
}
