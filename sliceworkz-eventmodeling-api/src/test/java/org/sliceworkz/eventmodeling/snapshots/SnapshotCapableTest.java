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
package org.sliceworkz.eventmodeling.snapshots;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

public class SnapshotCapableTest {

	/**
	 * Test implementation of SnapshotCapable for testing the default key method
	 */
	private static class TestSnapshotCapable implements SnapshotCapable<String> {
		@Override
		public String takeSnapshot() {
			return null;
		}

		@Override
		public void fromSnapshot(String snapshot) {
		}

		@Override
		public String version() {
			return "1.0";
		}
	}

	private final SnapshotCapable<String> snapshotCapable = new TestSnapshotCapable();

	@Test
	void testKeyWithSingleTag() {
		Tags tags = Tags.of("accountId", "123");
		String key = snapshotCapable.key("Account", tags);
		assertEquals("Account/accountId-123", key);
	}

	@Test
	void testKeyWithMultipleTags() {
		Tags tags = Tags.of(
			new Tag("accountId", "123"),
			new Tag("customerId", "456")
		);
		String key = snapshotCapable.key("Account", tags);
		// Tags should be sorted alphabetically by key
		assertEquals("Account/accountId-123/customerId-456", key);
	}

	@Test
	void testKeyWithMultipleTagsDeterministicOrder() {
		// Create tags in different orders
		Tags tags1 = Tags.of(
			new Tag("customerId", "456"),
			new Tag("accountId", "123")
		);
		Tags tags2 = Tags.of(
			new Tag("accountId", "123"),
			new Tag("customerId", "456")
		);

		String key1 = snapshotCapable.key("Account", tags1);
		String key2 = snapshotCapable.key("Account", tags2);

		// Both should produce the same key regardless of insertion order
		assertEquals(key1, key2);
		assertEquals("Account/accountId-123/customerId-456", key1);
	}

	@Test
	void testKeyWithNullName() {
		Tags tags = Tags.of("accountId", "123");
		String key = snapshotCapable.key(null, tags);
		assertEquals("/accountId-123", key);
	}

	@Test
	void testKeyWithNullIdentity() {
		String key = snapshotCapable.key("Account", (Tags) null);
		assertEquals("Account", key);
	}

	@Test
	void testKeyWithNullTagKey() {
		Tags tags = Tags.of(new Tag(null, "123"));
		String key = snapshotCapable.key("Account", tags);
		assertEquals("Account/-123", key);
	}

	@Test
	void testKeyWithNullTagValue() {
		Tags tags = Tags.of(new Tag("accountId", null));
		String key = snapshotCapable.key("Account", tags);
		assertEquals("Account/accountId-", key);
	}

	@Test
	void testKeyWithNullTagKeyAndValue() {
		Tags tags = Tags.of(new Tag(null, null));
		String key = snapshotCapable.key("Account", tags);
		assertEquals("Account/-", key);
	}

	@Test
	void testKeyWithMixedNullTags() {
		Tags tags = Tags.of(
			new Tag("accountId", "123"),
			new Tag(null, "456"),
			new Tag("customerId", null),
			new Tag(null, null)
		);
		String key = snapshotCapable.key("Account", tags);
		// Null keys should sort first (empty string sorts before any character)
		assertEquals("Account/-/-456/accountId-123/customerId-", key);
	}

	@Test
	void testKeyWithEmptyName() {
		Tags tags = Tags.of("accountId", "123");
		String key = snapshotCapable.key("", tags);
		assertEquals("/accountId-123", key);
	}

	@Test
	void testKeyWithEmptyTagValues() {
		Tags tags = Tags.of(
			new Tag("accountId", ""),
			new Tag("customerId", "")
		);
		String key = snapshotCapable.key("Account", tags);
		assertEquals("Account/accountId-/customerId-", key);
	}

	@Test
	void testKeyWithComplexScenario() {
		// Test a realistic scenario with multiple sorted tags
		Tags tags = Tags.of(
			new Tag("orderId", "ord-789"),
			new Tag("customerId", "cust-456"),
			new Tag("accountId", "acc-123")
		);
		String key = snapshotCapable.key("OrderAggregate", tags);
		assertEquals("OrderAggregate/accountId-acc-123/customerId-cust-456/orderId-ord-789", key);
	}

	@Test
	void testKeyWithNullNameAndNullIdentity() {
		String key = snapshotCapable.key(null, (Tags) null);
		assertEquals("", key);
	}

	@Test
	void testKeyWithSpecialCharactersInValues() {
		Tags tags = Tags.of(
			new Tag("userId", "user/123"),
			new Tag("sessionId", "sess-456")
		);
		String key = snapshotCapable.key("UserSession", tags);
		// Special characters in values should be preserved
		assertEquals("UserSession/sessionId-sess-456/userId-user/123", key);
	}

}
