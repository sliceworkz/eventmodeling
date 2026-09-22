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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins that choosing what a command decides on is structural: a {@link Command} or an
 * {@link OutboundCommand} that never calls {@code decisionModels(...)} or {@code noDecisionModels()}
 * does not compile.
 * <p>
 * The choice is what pins the consistency boundary and produces the {@link CommandResult} the command
 * raises its events on, and those two calls are the only source of one — so declaring {@code execute}
 * to return the result is what turns "forgot to decide" from a first-execution
 * {@code IllegalStateException} into a compile error. The guarantee is a compile-time one, so the only
 * way to test it is to compile: each probe below is handed to javac against this module's classes, and
 * the test asserts which probes it accepts and which it rejects.
 * <p>
 * Two things it deliberately does not claim. A command can still return {@code null}, which no
 * signature can prevent, and a {@link CommandWithResult} cannot be held to this at all, since its
 * return slot carries the caller's response value. Both land on the runtime backstop, which
 * {@code CommandWithoutDecisionModelsTest} in the tests module pins.
 */
public class CommandResultIsStructuralTest {

	private static final String PROBE_PRELUDE = """
			import org.sliceworkz.eventmodeling.commands.Command;
			import org.sliceworkz.eventmodeling.commands.CommandContext;
			import org.sliceworkz.eventmodeling.commands.CommandResult;
			import org.sliceworkz.eventmodeling.commands.CommandWithResult;
			import org.sliceworkz.eventmodeling.commands.DecisionModel;
			import org.sliceworkz.eventmodeling.commands.OutboundCommand;
			import org.sliceworkz.eventmodeling.commands.OutboundCommandContext;
			import org.sliceworkz.eventstore.events.Event;
			import org.sliceworkz.eventstore.events.Tags;
			import org.sliceworkz.eventstore.query.EventQuery;

			class Probe {
				interface BankingEvent { }
				record AccountOpened ( String id ) implements BankingEvent { }
				interface BankingOutboundEvent { }
				record AccountAnnounced ( String id ) implements BankingOutboundEvent { }

				static class Balance implements DecisionModel<BankingEvent> {
					@Override public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
					@Override public void when ( Event<BankingEvent> event ) { }
				}

			""";

	private static final String PROBE_EPILOGUE = "}\n";

	@TempDir
	Path outputDirectory;

	@Test
	void aCommandThatDecidesAndReturnsWhatItDecidedOnCompiles ( ) {
		assertAccepted("""
					static class OpenAccountCommand implements Command<BankingEvent> {
						@Override
						public CommandResult<BankingEvent, BankingEvent> execute ( CommandContext<BankingEvent, BankingEvent> context ) {
							Balance balance = new Balance();
							return context.decisionModels(balance)
									.raiseEvent(new AccountOpened("1"), Tags.none());
						}
					}
				""");
	}

	@Test
	void aCommandThatDecidesOnNothingCompilesToo ( ) {
		// deciding on nothing is legitimate — it just has to be said
		assertAccepted("""
					static class ImportAccountCommand implements Command<BankingEvent> {
						@Override
						public CommandResult<BankingEvent, BankingEvent> execute ( CommandContext<BankingEvent, BankingEvent> context ) {
							return context.noDecisionModels().raiseEvent(new AccountOpened("1"), Tags.none());
						}
					}
				""");
	}

	@Test
	void aCommandThatNeverChoosesDoesNotCompile ( ) {
		// the whole point: the body below is what a forgotten choice looks like, and there is no
		// signature it fits — void does not override execute, and nothing but decisionModels(...) or
		// noDecisionModels() produces the CommandResult that does
		assertRejected("""
					static class ForgetfulCommand implements Command<BankingEvent> {
						@Override
						public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {
						}
					}
				""");
	}

	@Test
	void aCommandThatDecidesButStillReturnsVoidDoesNotCompile ( ) {
		// the mistake is the signature, not the body: a command that did everything right except say
		// so is refused just the same, which is what keeps the return type from being decorative
		assertRejected("""
					static class VoidCommand implements Command<BankingEvent> {
						@Override
						public void execute ( CommandContext<BankingEvent, BankingEvent> context ) {
							context.noDecisionModels().raiseEvent(new AccountOpened("1"), Tags.none());
						}
					}
				""");
	}

	@Test
	void theSameHoldsForAnOutboundCommand ( ) {
		assertAccepted("""
					static class AnnounceAccountCommand implements OutboundCommand<BankingEvent, BankingOutboundEvent> {
						@Override
						public CommandResult<BankingEvent, BankingOutboundEvent> execute ( OutboundCommandContext<BankingEvent, BankingOutboundEvent> context ) {
							return context.noDecisionModels()
									.raiseEvent(new AccountAnnounced("1"), Tags.none(), "announce/1");
						}
					}
				""");
		assertRejected("""
					static class ForgetfulOutboundCommand implements OutboundCommand<BankingEvent, BankingOutboundEvent> {
						@Override
						public void execute ( OutboundCommandContext<BankingEvent, BankingOutboundEvent> context ) {
						}
					}
				""");
	}

	@Test
	void aCommandReturningSomethingThatIsNotAResultDoesNotCompile ( ) {
		// the auxiliary read is on the context too, and is not a decision — returning one says the
		// command decided on something it did not
		assertRejected("""
					static class ConfusedCommand implements Command<BankingEvent> {
						@Override
						public CommandResult<BankingEvent, BankingEvent> execute ( CommandContext<BankingEvent, BankingEvent> context ) {
							return new Balance();
						}
					}
				""");
	}

	@Test
	void aCommandWithAResultIsDeliberatelyNotHeldToThis ( ) {
		// its return slot carries the caller's response, so the choice cannot be demanded here; this
		// compiles, and fails on its first execution instead
		assertAccepted("""
					static class ForgetfulCommandWithResult implements CommandWithResult<BankingEvent, String> {
						@Override
						public String execute ( CommandContext<BankingEvent, BankingEvent> context ) {
							return "answered, but nothing was decided on";
						}
					}
				""");
	}

	// ---------------------------------------------------------------- harness

	private void assertAccepted ( String declaration ) {
		List<Diagnostic<? extends JavaFileObject>> errors = compile(declaration);
		assertTrue(errors.isEmpty(), () -> "javac rejected: " + declaration + "\n" + errors.stream()
				.map(d -> d.getLineNumber() + ": " + d.getMessage(Locale.ENGLISH))
				.collect(Collectors.joining("\n")));
	}

	private void assertRejected ( String declaration ) {
		assertFalse(compile(declaration).isEmpty(), "javac accepted: " + declaration);
	}

	private List<Diagnostic<? extends JavaFileObject>> compile ( String declaration ) {
		String source = PROBE_PRELUDE + declaration + PROBE_EPILOGUE;
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertNotNull(javac, "no system compiler: the tests need a JDK, not a JRE");
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		JavaFileObject probe = new SimpleJavaFileObject(URI.create("string:///Probe.java"), JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent ( boolean ignoreEncodingErrors ) {
				return source;
			}
		};
		List<String> options = List.of(
				"-classpath", classpath(),
				"-d", outputDirectory.toString(),
				"-proc:none",
				"-implicit:none");
		try ( StandardJavaFileManager fileManager = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8) ) {
			javac.getTask(null, fileManager, diagnostics, options, null, List.of(probe)).call();
		} catch ( IOException e ) {
			throw new IllegalStateException(e);
		}
		return diagnostics.getDiagnostics().stream()
				.filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
				.collect(Collectors.toList());
	}

	/** the classes the probe compiles against: this module's own, plus whatever the test JVM was started with */
	private static String classpath ( ) {
		List<String> entries = new ArrayList<>();
		entries.add(new File(Command.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath());
		String inherited = System.getProperty("java.class.path");
		if ( inherited != null && !inherited.isBlank() ) {
			entries.add(inherited);
		}
		return String.join(File.pathSeparator, entries);
	}
}
