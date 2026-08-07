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
package org.sliceworkz.eventmodeling.commands;

public sealed interface AbstractCommand<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> permits Command, OutboundCommand {

	default String commandName ( ) {
		return commandNameOf(this.getClass());
	}

	/**
	 * The name a command is reported under: its simple class name without the conventional
	 * {@code Command} suffix.
	 * <p>
	 * Single definition of the convention, shared by {@link CommandWithResult} and by the command
	 * registration on the bounded context builder, so that a command declared on a feature slice
	 * before it has ever run and the same command observed running are named identically. A command
	 * that overrides {@link #commandName()} breaks that correspondence, since the override cannot be
	 * consulted without an instance.
	 * <p>
	 * The name is never empty. A command declared as an anonymous class has no simple name, and a
	 * class named exactly {@code Command} is nothing but the suffix; both would otherwise be reported
	 * — and traced onto their events — under no name at all. Those fall back to the last segment of
	 * the binary name, which still says where the command was declared ({@code MyTest$1}).
	 */
	static String commandNameOf ( Class<?> commandClass ) {
		String simpleName = commandClass.getSimpleName();
		String withoutSuffix = simpleName.endsWith("Command")
				? simpleName.substring(0, simpleName.length() - "Command".length())
				: simpleName;
		if ( !withoutSuffix.isEmpty() ) {
			return withoutSuffix;
		}
		String binaryName = commandClass.getName();
		return binaryName.substring(binaryName.lastIndexOf('.') + 1);
	}

	// execute(...) lives on the permits rather than here, because the two shapes are handed different
	// contexts: a Command gets the full CommandContext, an OutboundCommand the narrower
	// OutboundCommandContext without decisionModels(...) — see there for why.

}
