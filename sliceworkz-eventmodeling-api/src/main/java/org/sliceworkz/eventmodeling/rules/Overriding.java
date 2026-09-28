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
package org.sliceworkz.eventmodeling.rules;

/**
 * Implemented by a command that accepts {@link Overrides}: the kernel asks it for the overrides its caller
 * requested and judges every {@link EnforcementLevel#isOverridable() overridable} violation against them.
 * <pre>{@code
 * public record WithdrawCommand ( AccountId accountId, BigDecimal amount, Overrides overrides )
 *         implements Command<BankingEvent>, Overriding { ... }
 * }</pre>
 * Overrides are an input of the command rather than a parameter of {@code execute(...)} on purpose. The
 * command's caller is the one that knows what the user asked for, every existing {@code execute} and
 * {@code executeWithRetry} overload keeps working unchanged, and whatever builds a command without a user in
 * front of it — an automation, a translator, a test — overrides nothing unless it says so. A retry
 * re-executes the same command, so it carries the same overrides into every attempt, and re-judges them
 * against the facts as they are then.
 */
public interface Overriding {

	/**
	 * @return the overrides the caller asked for; never {@code null} — {@link Overrides#none()} for none
	 */
	Overrides overrides ( );

}
