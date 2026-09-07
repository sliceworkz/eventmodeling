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

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

/**
 * A kind of thing with identity that events are about: account, customer, payment, month.
 *
 * <p>An entity is what a tag key names. Tagging an event with {@code account:123} says the event is
 * about account 123, and it is what a query for "everything about account 123" and a consistency
 * boundary around it are built on. This class ties the three things a domain needs for that
 * together: the name that becomes the tag key, the {@link EntityId} type that identifies one
 * instance, and the tags that put an event on record as being about one.
 *
 * <p>Declare one per kind of entity, next to its id, and use it from every command and read model:
 *
 * <pre>{@code
 * public interface BankingDomain {
 *
 *     record AccountId  ( String value ) implements EntityId { }
 *     record CustomerId ( String value ) implements EntityId { }
 *
 *     Entity<AccountId>  ACCOUNT  = Entity.of("account",  AccountId::new);
 *     Entity<CustomerId> CUSTOMER = Entity.of("customer", CustomerId::new);
 * }
 *
 * // in a command
 * AccountId accountId = ACCOUNT.newId();
 * result.raiseEvent(new AccountOpened(accountId, customerId, LocalDate.now()),
 *         Tags.of(ACCOUNT.tag(accountId), CUSTOMER.tag(customerId)));
 *
 * // in a read model
 * return EventQuery.forEvents(EventTypesFilter.any(), ACCOUNT.tags(accountId));
 * }</pre>
 *
 * <p>The id type is part of the entity, which is what makes the tags type-safe:
 * {@code ACCOUNT.tag(customerId)} does not compile. The one-line id record per entity is the price of
 * that, and it is the whole price — minting ({@link #newId()}), normalisation ({@link #id(String)})
 * and reading an id back off an event ({@link #idIn(Tags)}) live here, so the record stays a plain
 * carrier that Jackson can reconstruct from any value ever stored.
 *
 * <p>Two entities are equal when their names are equal, whatever id type they were declared with.
 * The name is the tag key, and it is what an event carries; the id type is a compile-time convenience
 * of the code that declared it. The name is stored data, so treat it like a column name: renaming an
 * entity changes the key on every new event and none of the old ones.
 *
 * <p>Not every tag is an entity. A tag whose value is not the identity of anything — a region, a
 * channel, a flag — is an ordinary {@link Tag#of(String, String)}.
 *
 * @param <ID> the type identifying one instance of this entity
 */
public final class Entity<ID extends EntityId> {

	private final String name;
	private final Function<String, ID> ids;

	private Entity ( String name, Function<String, ID> ids ) {
		this.name = name;
		this.ids = ids;
	}

	/**
	 * Declares an entity.
	 *
	 * @param name the entity's name, which becomes the key of its tags; trimmed, and neither null nor
	 *             blank nor containing a {@code ':'} (which {@link Tag} rejects as a key)
	 * @param ids  how to make an id of this entity out of its stored value, typically the id record's
	 *             constructor reference
	 * @throws IllegalArgumentException for a null or blank name
	 */
	public static <ID extends EntityId> Entity<ID> of ( String name, Function<String, ID> ids ) {
		if ( name == null || name.isBlank() ) {
			throw new IllegalArgumentException("an entity needs a name");
		}
		if ( name.indexOf(Tag.SEPARATOR) >= 0 ) {
			throw new IllegalArgumentException("an entity name cannot contain '" + Tag.SEPARATOR + "', it is the tag key: " + name);
		}
		Objects.requireNonNull(ids, "an entity needs a way to construct its ids");
		return new Entity<>(name.strip(), ids);
	}

	/**
	 * The entity's name, which is the key of its tags.
	 */
	public String name ( ) {
		return name;
	}

	/**
	 * Mints a fresh, random id for a new instance of this entity.
	 */
	public ID newId ( ) {
		return ids.apply(UUID.randomUUID().toString());
	}

	/**
	 * An id of this entity from its value, normalised: leading and trailing whitespace is stripped, so
	 * that equality and tag matching see one spelling. This is where a stored value, a request
	 * parameter or a tag value becomes an id.
	 *
	 * @throws IllegalArgumentException for a null or blank value, which identifies nothing
	 */
	public ID id ( String value ) {
		if ( value == null || value.isBlank() ) {
			throw new IllegalArgumentException("an id of " + name + " needs a value");
		}
		return ids.apply(value.strip());
	}

	/**
	 * The tag saying an event is about the given instance of this entity: {@code name:value}.
	 *
	 * @throws IllegalArgumentException for a null id
	 */
	public Tag tag ( ID id ) {
		if ( id == null ) {
			throw new IllegalArgumentException("a tag of " + name + " needs an id");
		}
		return Tag.of(name, id.value());
	}

	/**
	 * The tag saying an event is about this entity without naming an instance: the bare key. A flag,
	 * for when the presence of the tag is itself the fact. Matching is exact containment, so this tag
	 * does <em>not</em> match events tagged with an instance of the entity.
	 */
	public Tag tag ( ) {
		return Tag.of(name);
	}

	/**
	 * {@link #tag(EntityId)} as a {@link Tags}, which is what a query and an append take.
	 */
	public Tags tags ( ID id ) {
		return Tags.of(tag(id));
	}

	/**
	 * The id of this entity an event carries, read back off its tags — the inverse of
	 * {@link #tag(EntityId)}. Empty when the tags hold no tag of this entity, or only the bare flag.
	 */
	public Optional<ID> idIn ( Tags tags ) {
		return tags.tag(name).map(Tag::value).filter(value -> value != null && !value.isBlank()).map(this::id);
	}

	@Override
	public boolean equals ( Object other ) {
		return other instanceof Entity<?> that && name.equals(that.name);
	}

	@Override
	public int hashCode ( ) {
		return name.hashCode();
	}

	@Override
	public String toString ( ) {
		return name;
	}

}
