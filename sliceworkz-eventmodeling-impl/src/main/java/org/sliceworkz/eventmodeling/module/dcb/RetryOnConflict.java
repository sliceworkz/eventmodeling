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

import java.util.function.Supplier;

import org.sliceworkz.eventmodeling.commands.RetryPolicy;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * The re-execution loop an {@link org.sliceworkz.eventmodeling.commands.Execution} asks for: attempts the
 * execution until it succeeds, fails with anything but an {@link OptimisticLockingException}, or the
 * policy's attempts are exhausted — then rethrows the last conflict unchanged, the earlier ones attached as
 * suppressed. Each attempt is a whole execution, so it re-projects the command's decision models.
 */
public final class RetryOnConflict {

	private RetryOnConflict ( ) { }

	public static <RESULT> RESULT run ( RetryPolicy policy, Supplier<RESULT> attempt ) {
		OptimisticLockingException[] earlier = new OptimisticLockingException[policy.maxAttempts() - 1];
		for ( int attemptNo = 1; ; attemptNo++ ) {
			try {
				return attempt.get();
			} catch ( OptimisticLockingException conflict ) {	// only a DCB conflict is ever retried
				if ( attemptNo == policy.maxAttempts() ) {
					for ( int i = 0; i < attemptNo - 1; i++ ) {
						conflict.addSuppressed(earlier[i]);
					}
					throw conflict;								// the last conflict, unchanged
				}
				earlier[attemptNo - 1] = conflict;				// no delay: re-executing re-reads the new facts
			}
		}
	}

}
