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
package org.sliceworkz.eventmodeling.examples.banking.features.blockcardsofaccount;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.banking.ClosingTheBooks;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;

/**
 * Block Cards Of Account — a policy: whenever an account is frozen or closed, block its cards.
 * <p>
 * Account Frozen | Account Closed → Block Cards Of Account → Card Blocked (one per card still usable).
 * <ul>
 * <li>{@code fromNowOn()}: accounts frozen or closed before the policy was deployed are not revisited; it
 *     reacts to what happens after it first runs. A bank wanting its history cleaned up too would choose
 *     {@code fromTheBeginning()}, which is safe here because the command raises nothing for cards already
 *     blocked.</li>
 * <li>{@code stallOnRejection()}: the command rejects nothing by design, so a rejection would be a mistake,
 *     and skipping it would leave a frozen account's cards usable. The policy stops at that event instead
 *     (reported {@code PolicyFailed}) until the cause is fixed or an operator skips it.</li>
 * </ul>
 * A policy rather than a todo list and an automation, because the reaction needs nothing a policy lacks: no
 * port, no deadline, nothing gathered from several facts — one event, one command, and the command finds the
 * cards itself.
 */
@FeatureSlice(chapter = "Cards")
public class BlockCardsOfAccountFeatureSlice implements Slice<ClosingTheBooks> {

	@Override
	public void configureAutomation ( BoundedContextBuilder<ClosingTheBooks> builder ) {
		builder
			.command(BlockCardsOfAccountCommand.class)
			.policy(new BlockCardsOfAccountPolicy())
				.fromNowOn()
				.stallOnRejection();
	}

}
