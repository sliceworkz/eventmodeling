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
package org.sliceworkz.eventmodeling.examples.localfirst.crdt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A multi-value register: a value written by operations, keeping every write no later write has seen.
 *
 * <p>A write removes the writes in its causal past and is added beside the writes it is concurrent with.
 * So after a partition heals, the register holds one value per side that changed it without knowing about
 * the other; which one counts is a domain decision ({@link #resolved(Comparator)}), taken the same way on
 * every replica. Applying the same set of writes in any causally consistent order gives the same register,
 * which is what makes it a CRDT.
 *
 * <p>Immutable: {@link #write} answers a new register.
 */
public final class MultiValueRegister<V> {

	public record Write<V> ( OperationStamp stamp, V value ) { }

	private final List<Write<V>> heads;

	private MultiValueRegister ( List<Write<V>> heads ) {
		this.heads = List.copyOf(heads);
	}

	public static <V> MultiValueRegister<V> empty ( ) {
		return new MultiValueRegister<>(List.of());
	}

	public MultiValueRegister<V> write ( OperationStamp stamp, V value ) {
		boolean alreadyKnown = heads.stream().anyMatch(h -> h.stamp().equals(stamp) || stamp.happenedBefore(h.stamp()));
		if ( alreadyKnown ) {
			return this;
		}
		List<Write<V>> next = new ArrayList<>();
		for ( Write<V> head : heads ) {
			if ( !head.stamp().happenedBefore(stamp) ) {
				next.add(head);
			}
		}
		next.add(new Write<>(stamp, value));
		return new MultiValueRegister<>(next);
	}

	/** The writes no other write has seen, in an order every replica agrees on. */
	public List<Write<V>> writes ( ) {
		return heads.stream()
				.sorted(Comparator.comparing((Write<V> w) -> w.stamp().origin().value()).thenComparingLong(w -> w.stamp().counter()))
				.toList();
	}

	/** The values of the writes no other write has seen: one, unless replicas wrote concurrently. */
	public Set<V> values ( ) {
		return heads.stream().map(Write::value).collect(Collectors.toUnmodifiableSet());
	}

	/** Whether the current value came out of concurrent writes. */
	public boolean concurrentlyWritten ( ) {
		return heads.size() > 1;
	}

	/** The value that counts, by a rule every replica applies alike; {@code null} for a register never written. */
	public V resolved ( Comparator<? super V> priority ) {
		return heads.stream().map(Write::value).max(priority).orElse(null);
	}

}
