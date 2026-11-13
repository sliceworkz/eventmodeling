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
package org.sliceworkz.eventmodeling.module.dcb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sliceworkz.eventmodeling.module.dcb.TaggedBankEvent.ACCOUNT;
import static org.sliceworkz.eventmodeling.module.dcb.TaggedBankEvent.FROM_ACCOUNT;
import static org.sliceworkz.eventmodeling.module.dcb.TaggedBankEvent.TO_ACCOUNT;
import static org.sliceworkz.eventmodeling.module.dcb.TaggedBankEvent.TRANSFER;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.module.dcb.TaggedBankEvent.AccountId;
import org.sliceworkz.eventmodeling.module.dcb.TaggedBankEvent.MoneyDeposited;
import org.sliceworkz.eventmodeling.module.dcb.TaggedBankEvent.MoneyTransferred;
import org.sliceworkz.eventmodeling.testing.AbstractBoundedContextTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * A command raising a {@link org.sliceworkz.eventstore.events.Tagged} event passes only the tags its
 * payload does not carry: the event states the rest, the store merges them, and a tag the event requires
 * but the command forgot fails where the command raised it.
 * <p>
 * Role keys ({@code from-account}, {@code to-account}) sit beside the entity key: a decision on
 * everything about an account reads {@code account}, and one genuinely about what an account sent reads
 * {@code from-account}, so money arriving in that account is no new fact for it.
 */
public class TaggedEventsTest extends AbstractBoundedContextTest<TaggedBankEvent, MockInboundEvent, MockOutboundEvent> {

	private static final AccountId A = ACCOUNT.id("A");
	private static final AccountId B = ACCOUNT.id("B");
	private static final AccountId C = ACCOUNT.id("C");

	@Override
	public Class<TaggedBankEvent> domainEventType ( ) {
		return TaggedBankEvent.class;
	}

	@Override
	public Class<MockInboundEvent> inboundEventType ( ) {
		return MockInboundEvent.class;
	}

	@Override
	public Class<MockOutboundEvent> outboundEventType ( ) {
		return MockOutboundEvent.class;
	}

	@Override
	public void configure ( BoundedContextBuilder<?> builder ) {
	}

	/** Counts the events a filter selects. */
	static class Counting implements DecisionModel<TaggedBankEvent> {
		private final EventFilter filter;
		final AtomicInteger seen = new AtomicInteger();
		Counting ( EventFilter filter ) { this.filter = filter; }
		@Override public EventFilter eventFilter ( ) { return filter; }
		@Override public void when ( Event<TaggedBankEvent> event ) { seen.incrementAndGet(); }
	}

	/** Transfers from {@code from} to {@code to}, deciding on {@code boundary}; {@code between} runs after the read. */
	private static Command<TaggedBankEvent> transfer ( String id, AccountId from, AccountId to, EventFilter boundary, Runnable between, Tag... passed ) {
		return new Command<>() {
			@Override
			public void execute ( CommandContext<TaggedBankEvent, TaggedBankEvent> context ) {
				var result = context.decisionModels(new Counting(boundary));
				between.run();
				result.raiseEvent(new MoneyTransferred(TRANSFER.id(id), from, to, 10), passed);
			}
		};
	}

	private void deposit ( AccountId account ) {
		domainStream().append(AppendCriteria.none(), Event.of(new MoneyDeposited(account, 100), Tags.none()));
	}

	private List<Event<TaggedBankEvent>> transfers ( ) {
		return domainStream().query(EventFilter.forTypes(MoneyTransferred.class));
	}

	@ForEachBackend
	void aRaisedEventIsStoredWithTheTagsItStatesAndTheOnesPassed ( ) {
		kernel().execute(transfer("t1", A, B, EventFilter.forTags(ACCOUNT.tag(A)), () -> { }, Tag.of("channel", "atm")));

		Tags stored = transfers().getFirst().tags();
		assertTrue(stored.containsAll(Tags.of(TRANSFER.tag(TRANSFER.id("t1")), ACCOUNT.tag(A), ACCOUNT.tag(B),
				FROM_ACCOUNT.tag(A), TO_ACCOUNT.tag(B), Tag.of("channel", "atm"))), stored.toString());
		// and the kernel's own tags are kept beside them
		assertTrue(stored.tags().stream().anyMatch(t -> t.key() != null && t.key().startsWith("x-")), stored.toString());
	}

	@ForEachBackend
	void aRequiredTagTheCommandForgotFailsWhereItRaisedTheEvent ( ) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> kernel().execute(transfer("t1", A, B, EventFilter.forTags(ACCOUNT.tag(A)), () -> { })));

		assertTrue(refused.getMessage().contains("MoneyTransferred is raised without the tags it requires"), refused.getMessage());
		assertTrue(refused.getMessage().contains("'channel'"), refused.getMessage());
		assertTrue(transfers().isEmpty());
	}

	@ForEachBackend
	void aSeededEventIsTaggedAsProductionTagsIt ( ) {
		deposit(A);

		assertEquals(1, domainStream().query(EventFilter.forTags(ACCOUNT.tag(A))).size());
	}

	@ForEachBackend
	void aDecisionOnTheAccountConflictsWithMoneyArrivingInIt ( ) {
		deposit(A);

		assertThrows(OptimisticLockingException.class, () -> kernel().execute(
				transfer("t1", A, B, EventFilter.forTags(ACCOUNT.tag(A)), () -> deposit(A), Tag.of("channel", "atm"))));
	}

	@ForEachBackend
	void aDecisionOnWhatTheAccountSentDoesNot ( ) {
		deposit(A);

		// money arriving in A, by deposit and by transfer, is no new fact for what A sent
		assertDoesNotThrow(() -> kernel().execute(
				transfer("t1", A, B, EventFilter.forTags(FROM_ACCOUNT.tag(A)), () -> {
					deposit(A);
					kernel().execute(transfer("t0", C, A, EventFilter.forTags(FROM_ACCOUNT.tag(C)), () -> { }, Tag.of("channel", "web")));
				}, Tag.of("channel", "atm"))));

		// money A sent meanwhile is
		assertThrows(OptimisticLockingException.class, () -> kernel().execute(
				transfer("t2", A, B, EventFilter.forTags(FROM_ACCOUNT.tag(A)),
						() -> kernel().execute(transfer("t3", A, C, EventFilter.forTags(FROM_ACCOUNT.tag(A)), () -> { }, Tag.of("channel", "web"))),
						Tag.of("channel", "atm"))));
	}

	@ForEachBackend
	void aTransferToTheSameAccountKeepsBothRoles ( ) {
		kernel().execute(transfer("t1", A, A, EventFilter.forTags(ACCOUNT.tag(A)), () -> { }, Tag.of("channel", "atm")));

		Tags stored = transfers().getFirst().tags();
		assertEquals(1, stored.tags(ACCOUNT.name()).size());
		assertEquals(1, stored.tags(FROM_ACCOUNT.name()).size());
		assertEquals(1, stored.tags(TO_ACCOUNT.name()).size());
	}

}
