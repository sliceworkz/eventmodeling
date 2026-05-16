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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class CommandNameTest {

	@Test
	void commandSuffixIsStripped() {
		assertEquals("OpenAccount", new OpenAccountCommand().commandName());
	}

	@Test
	void nameWithoutCommandSuffixIsReturnedAsIs() {
		assertEquals("OpenAccount", new OpenAccount().commandName());
	}

	@Test
	void onlySingleTrailingCommandSuffixIsStripped() {
		assertEquals("DoStuffCommand", new DoStuffCommandCommand().commandName());
	}

	@Test
	void outboundCommandSuffixIsStripped() {
		assertEquals("PublishOutbound", new PublishOutboundCommand().commandName());
	}

	@Test
	void outboundWithoutCommandSuffixIsReturnedAsIs() {
		assertEquals("PublishOutbound", new PublishOutbound().commandName());
	}

	@Test
	void commandWithResultSuffixIsStripped() {
		assertEquals("CreateThing", new CreateThingCommand().commandName());
	}

	@Test
	void commandWithResultNameWithoutSuffixIsReturnedAsIs() {
		assertEquals("CreateThing", new CreateThing().commandName());
	}

	@Test
	void overriddenCommandNameIsReturnedAsIs() {
		assertEquals("explicit-name", new OverridingCommand().commandName());
	}

	// ════════════════════════════════════════════════════════════════════
	// COMMAND IMPLEMENTATIONS USED BY THE TESTS
	// ════════════════════════════════════════════════════════════════════

	static class OpenAccountCommand implements Command<Object> {
		@Override
		public void execute(CommandContext<Object, Object> context) {
		}
	}

	static class OpenAccount implements Command<Object> {
		@Override
		public void execute(CommandContext<Object, Object> context) {
		}
	}

	static class DoStuffCommandCommand implements Command<Object> {
		@Override
		public void execute(CommandContext<Object, Object> context) {
		}
	}

	static class PublishOutboundCommand implements OutboundCommand<Object, Object> {
		@Override
		public void execute(CommandContext<Object, Object> context) {
		}
	}

	static class PublishOutbound implements OutboundCommand<Object, Object> {
		@Override
		public void execute(CommandContext<Object, Object> context) {
		}
	}

	static class CreateThingCommand implements CommandWithResult<Object, String> {
		@Override
		public String execute(CommandContext<Object, Object> context) {
			return "ok";
		}
	}

	static class CreateThing implements CommandWithResult<Object, String> {
		@Override
		public String execute(CommandContext<Object, Object> context) {
			return "ok";
		}
	}

	static class OverridingCommand implements Command<Object> {
		@Override
		public String commandName() {
			return "explicit-name";
		}

		@Override
		public void execute(CommandContext<Object, Object> context) {
		}
	}

}
