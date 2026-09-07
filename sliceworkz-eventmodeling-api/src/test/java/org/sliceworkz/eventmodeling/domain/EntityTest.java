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
package org.sliceworkz.eventmodeling.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

public class EntityTest {

	record AccountId ( String value ) implements EntityId { }
	record CustomerId ( String value ) implements EntityId { }

	static final Entity<AccountId> ACCOUNT = Entity.of("account", AccountId::new);
	static final Entity<CustomerId> CUSTOMER = Entity.of("customer", CustomerId::new);

	@Test
	void theTagIsTheNameAndTheIdValue ( ) {
		Tag tag = ACCOUNT.tag(new AccountId("123"));
		assertEquals("account", tag.key());
		assertEquals("123", tag.value());
		assertEquals("account:123", tag.toString());
		assertEquals(Tag.parse("account:123"), tag);
	}

	@Test
	void tagsWrapsTheOneTag ( ) {
		assertEquals(Tags.of(Tag.of("account", "123")), ACCOUNT.tags(new AccountId("123")));
	}

	@Test
	void theBareTagIsAFlagWithoutAValue ( ) {
		Tag tag = ACCOUNT.tag();
		assertEquals("account", tag.key());
		assertNull(tag.value());
		assertEquals(Tag.parse("account"), tag);
	}

	@Test
	void anIdIsNormalised ( ) {
		assertEquals(new AccountId("123"), ACCOUNT.id("  123  "));
		assertEquals(ACCOUNT.tag(new AccountId("123")), ACCOUNT.tag(ACCOUNT.id("  123  ")));
	}

	@Test
	void aBlankIdIsRejected ( ) {
		assertThrows(IllegalArgumentException.class, ( ) -> ACCOUNT.id(null));
		assertThrows(IllegalArgumentException.class, ( ) -> ACCOUNT.id("   "));
		assertThrows(IllegalArgumentException.class, ( ) -> ACCOUNT.tag(null));
	}

	@Test
	void aFreshIdIsAUuidOfTheEntitysIdType ( ) {
		AccountId id = ACCOUNT.newId();
		UUID.fromString(id.value());
		assertNotEquals(id, ACCOUNT.newId());
	}

	@Test
	void theIdIsReadBackOffTheTags ( ) {
		AccountId account = ACCOUNT.newId();
		CustomerId customer = CUSTOMER.newId();
		Tags tags = Tags.of(ACCOUNT.tag(account), CUSTOMER.tag(customer), Tag.of("region", "EU"));

		assertEquals(Optional.of(account), ACCOUNT.idIn(tags));
		assertEquals(Optional.of(customer), CUSTOMER.idIn(tags));
		assertEquals(Optional.empty(), Entity.of("order", AccountId::new).idIn(tags));
		assertEquals(Optional.empty(), ACCOUNT.idIn(Tags.of(ACCOUNT.tag())));
		assertEquals(Optional.empty(), ACCOUNT.idIn(Tags.none()));
	}

	@Test
	void anEntityIsItsName ( ) {
		assertEquals("account", ACCOUNT.name());
		assertEquals("account", ACCOUNT.toString());
		assertEquals(Entity.of("  account ", AccountId::new), ACCOUNT);
		assertEquals(Entity.of("account", CustomerId::new), ACCOUNT);
		assertEquals(Entity.of("account", CustomerId::new).hashCode(), ACCOUNT.hashCode());
		assertNotEquals(CUSTOMER, ACCOUNT);
	}

	@Test
	void anEntityNeedsAUsableName ( ) {
		assertThrows(IllegalArgumentException.class, ( ) -> Entity.of(null, AccountId::new));
		assertThrows(IllegalArgumentException.class, ( ) -> Entity.of("  ", AccountId::new));
		assertThrows(IllegalArgumentException.class, ( ) -> Entity.of("a:b", AccountId::new));
		assertThrows(NullPointerException.class, ( ) -> Entity.of("account", null));
	}

	@Test
	void twoIdTypesWithOneValueAreTwoIds ( ) {
		// ACCOUNT.tag(CUSTOMER.newId()) does not compile, which is the point of an id record per
		// entity; what remains to assert at runtime is that the records do not collapse on their value
		assertNotEquals(new AccountId("1"), new CustomerId("1"));
	}

}
