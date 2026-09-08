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

/**
 * The identity of one entity: one account, one customer, one payment.
 *
 * <p>An application declares one implementation per kind of entity, and it is one line:
 *
 * <pre>{@code
 * public record AccountId ( String value ) implements EntityId { }
 * public record CustomerId ( String value ) implements EntityId { }
 * }</pre>
 *
 * <p>The record is the whole point. {@code AccountOpened(AccountId accountId, CustomerId customerId)}
 * cannot be constructed with its arguments swapped, a read model asking for a {@code CustomerId} cannot
 * be handed an account, and {@link Entity#tag(EntityId)} only accepts the id of the entity it tags.
 * A single shared id type would let every one of those compile.
 *
 * <p>Ids are minted and normalised by the {@link Entity} they belong to ({@link Entity#newId()},
 * {@link Entity#id(String)}), so the record itself stays a plain carrier with a lenient canonical
 * constructor. That is deliberate: an id is carried inside event payloads, and Jackson reconstructs a
 * payload record through its canonical constructor on every read of history, so a constructor that
 * throws would turn a tightened rule into a poison event. Put validation in the factory, never in the
 * record.
 *
 * <p>Keep the component named {@code value}. The class name is not part of the stored form, the
 * component name is: an id is stored as {@code {"value":"..."}} inside the event payload, and renaming
 * the component breaks reads of every event already written.
 */
public interface EntityId {

	/**
	 * The identifying value, as stored and as it appears as the value of the entity's tag.
	 */
	String value ( );

}
