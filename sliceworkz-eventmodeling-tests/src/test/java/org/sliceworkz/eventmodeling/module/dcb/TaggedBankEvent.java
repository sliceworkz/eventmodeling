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

import java.util.Set;

import org.sliceworkz.eventmodeling.domain.Entity;
import org.sliceworkz.eventmodeling.domain.EntityId;
import org.sliceworkz.eventstore.events.TagRequirement;
import org.sliceworkz.eventstore.events.Tagged;
import org.sliceworkz.eventstore.events.Tags;

/**
 * A small bank whose events state their own tags: each account it is about under {@code account}, and
 * on a transfer each account also under the key of its role, beside the entity's.
 */
public sealed interface TaggedBankEvent extends Tagged {

	record AccountId ( String value ) implements EntityId { }
	record TransferId ( String value ) implements EntityId { }

	Entity<AccountId> ACCOUNT = Entity.of("account", AccountId::new);
	Entity<TransferId> TRANSFER = Entity.of("transfer", TransferId::new);

	/** Role keys: the same id type as the entity, a key of their own, always beside the entity's. */
	Entity<AccountId> FROM_ACCOUNT = Entity.of("from-account", AccountId::new);
	Entity<AccountId> TO_ACCOUNT = Entity.of("to-account", AccountId::new);

	record MoneyDeposited ( AccountId account, long amount ) implements TaggedBankEvent {
		@Override
		public Tags eventTags ( ) {
			return Tags.of(ACCOUNT.tag(account));
		}
	}

	/** About two accounts, each also under its role; the channel is not in the payload, so the command passes it. */
	record MoneyTransferred ( TransferId transferId, AccountId from, AccountId to, long amount ) implements TaggedBankEvent {
		@Override
		public Tags eventTags ( ) {
			return Tags.of(TRANSFER.tag(transferId), ACCOUNT.tag(from), ACCOUNT.tag(to), FROM_ACCOUNT.tag(from), TO_ACCOUNT.tag(to));
		}

		@Override
		public Set<TagRequirement> tagRequirements ( ) {
			return Set.of(TagRequirement.of(ACCOUNT.name()).between(1, 2),   // one for a transfer to the same account
					TagRequirement.of("channel").exactlyOne());
		}
	}

}
