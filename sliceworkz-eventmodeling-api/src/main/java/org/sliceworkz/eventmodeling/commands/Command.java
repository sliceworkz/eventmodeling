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

/**
 * A command: decides on decision models projected from the bounded context's domain stream and raises
 * domain events, appended to that same stream in one append guarded by the boundary the models were
 * read within. See {@link CommandContext}.
 * <p>
 * A command raises domain events only. Telling the outside world about a fact is the job of a
 * {@link org.sliceworkz.eventmodeling.outbound.Publisher}, which maps the domain events a command raised
 * into outbound events after they are stored. {@link CommandWithResult} is the other shape, for a
 * command whose caller needs what it decided.
 *
 * @param <DOMAIN_EVENT_TYPE> the base type of domain events in the bounded context
 */
public interface Command<DOMAIN_EVENT_TYPE> {

	void execute ( CommandContext<DOMAIN_EVENT_TYPE, DOMAIN_EVENT_TYPE> context );

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

}
