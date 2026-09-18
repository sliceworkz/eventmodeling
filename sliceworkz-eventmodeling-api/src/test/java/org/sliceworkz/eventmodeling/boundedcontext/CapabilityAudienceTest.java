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
package org.sliceworkz.eventmodeling.boundedcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Pins that narrowing a bounded context to one audience really does leave the other audiences out.
 * <p>
 * {@link BoundedContext} carries everything, because whatever built it needs everything; every other
 * caller is meant to hold one of the narrower interfaces {@link AllCapabilities} composes — {@link
 * ApplicationCapabilities} for code that decides and reads, {@link OperationsCapabilities} for an
 * operator's tooling, {@link PrivacyCapability} for an erasure request, {@link
 * org.sliceworkz.eventmodeling.events.ProvidedEventCapability} for the escape hatch. What makes that
 * worth doing is that the compiler enforces it, so the only way to test it is to compile: each probe
 * below is handed to javac against this module's classes, and the test asserts which probes it
 * accepts and which it rejects.
 * <p>
 * Two regressions this guards against, both silent otherwise. A capability folded into a narrow
 * interface — {@link LifecycleCapability} reachable from the application surface, say — gives every
 * holder of that surface a reach nobody chose, and no test notices, because the extra method is
 * simply never called through it. And a narrowing that stops compiling at all pushes callers back to
 * holding the whole context, which is the reach this exists to remove.
 */
public class CapabilityAudienceTest {

	private static final String PROBE_PRELUDE = """
			import org.sliceworkz.eventmodeling.aggregates.Aggregate;
			import org.sliceworkz.eventmodeling.boundedcontext.AllCapabilities;
			import org.sliceworkz.eventmodeling.boundedcontext.ApplicationCapabilities;
			import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
			import org.sliceworkz.eventmodeling.boundedcontext.OperationsCapabilities;
			import org.sliceworkz.eventmodeling.boundedcontext.PrivacyCapability;
			import org.sliceworkz.eventmodeling.boundedcontext.ProcessorKind;
			import org.sliceworkz.eventmodeling.commands.Command;
			import org.sliceworkz.eventmodeling.commands.RetryPolicy;
			import org.sliceworkz.eventmodeling.events.ProvidedEventCapability;
			import org.sliceworkz.eventmodeling.inbound.TranslationCapability;
			import org.sliceworkz.eventmodeling.readmodels.ReadModel;
			import org.sliceworkz.eventstore.events.Tags;
			import org.sliceworkz.eventstore.shredding.ErasureReason;

			class Probe {
				interface BankingEvent { }
				interface BankingInboundEvent { }
				interface BankingOutboundEvent { }

				interface Banking extends BoundedContext<BankingEvent, BankingInboundEvent, BankingOutboundEvent> { }

				abstract static class AccountDetails implements ReadModel<BankingEvent> { }
				abstract static class Account implements Aggregate<BankingEvent> { }
				abstract static class OpenAccount implements Command<BankingEvent> { }

				void probe ( Banking owner,
						ApplicationCapabilities<BankingEvent, BankingOutboundEvent> application,
						TranslationCapability<BankingInboundEvent> inbound,
						OperationsCapabilities operations,
						PrivacyCapability privacy,
						ProvidedEventCapability<BankingEvent> provided,
						OpenAccount command,
						BankingEvent domainEvent,
						BankingInboundEvent inboundEvent ) {
			""";

	private static final String PROBE_EPILOGUE = """
				}
			}
			""";

	/** javac's key for "cannot find symbol", which is how a method absent from the reference type is rejected */
	private static final String NO_SUCH_METHOD = "compiler.err.cant.resolve.location.args";

	/** javac's key for "incompatible types" */
	private static final String INCOMPATIBLE_TYPES = "compiler.err.prob.found.req";

	@TempDir
	Path outputDirectory;

	// ---------------------------------------------------------------- the application surface

	@Test
	void applicationCodeDecidesAndReads ( ) {
		assertAccepted("application.execute(command);");
		assertAccepted("application.execute(command, \"key\");");
		assertAccepted("application.executeWithRetry(command, RetryPolicy.DEFAULT);");
		assertAccepted("AccountDetails d = application.read(AccountDetails.class, \"id\");");
		assertAccepted("Account a = application.aggregate(Account.class, Tags.of(\"account\", \"1\"));");
	}

	@Test
	void applicationCodeCannotStopTheContextItWasHanded ( ) {
		// what an application surface must not carry: the lifecycle belongs to whatever built the context
		assertRejected("application.terminate();", NO_SUCH_METHOD);
		assertRejected("application.stop();", NO_SUCH_METHOD);
		assertRejected("application.start();", NO_SUCH_METHOD);
	}

	@Test
	void applicationCodeCannotEraseAPerson ( ) {
		// an erasure answers a request from a person, under an authority a controller does not carry
		assertRejected("application.erase(\"customer\", \"alice-42\", ErasureReason.of(\"why\"));", NO_SUCH_METHOD);
		assertAccepted("privacy.erase(\"customer\", \"alice-42\", ErasureReason.of(\"why\"));");
	}

	@Test
	void applicationCodeCannotStopAnAutomationOrAProcessor ( ) {
		assertRejected("application.stopAutomation(\"SomeAutomation\");", NO_SUCH_METHOD);
		assertRejected("application.stopProcessor(ProcessorKind.READ_MODEL, \"SomeReadModel\");", NO_SUCH_METHOD);
		assertRejected("application.automations();", NO_SUCH_METHOD);
	}

	@Test
	void applicationCodeCannotAppendADomainEventNoCommandRaised ( ) {
		// the escape hatch: no decision model read, no boundary checked. Reached by naming
		// ProvidedEventCapability, never by holding the application surface
		assertRejected("application.event(domainEvent);", NO_SUCH_METHOD);
		assertAccepted("provided.event(domainEvent);");
	}

	@Test
	void applicationCodeCannotFeedTheDomainExternalEvents ( ) {
		assertRejected("application.incoming(inboundEvent);", NO_SUCH_METHOD);
		assertAccepted("inbound.incoming(inboundEvent);");
		assertAccepted("inbound.translate(inboundEvent);");
	}

	@Test
	void applicationCodeCannotReachThePortsOrTheSliceInventory ( ) {
		assertRejected("application.port(Runnable.class);", NO_SUCH_METHOD);
		assertRejected("application.getDeployedFeatureSlices();", NO_SUCH_METHOD);
	}

	// ---------------------------------------------------------------- the operator surface

	@Test
	void anOperatorSeesAndMovesTheProcessors ( ) {
		assertAccepted("operations.automations();");
		assertAccepted("operations.restartAutomation(\"SomeAutomation\");");
		assertAccepted("operations.stopAutomation(\"SomeAutomation\");");
		assertAccepted("operations.processors();");
		assertAccepted("operations.restartProcessor(ProcessorKind.READ_MODEL, \"SomeReadModel\");");
		assertAccepted("operations.stopProcessor(ProcessorKind.READ_MODEL, \"SomeReadModel\");");
	}

	@Test
	void anOperatorDecidesNothingOnTheApplicationsBehalf ( ) {
		assertRejected("operations.execute(command);", NO_SUCH_METHOD);
		assertRejected("operations.read(AccountDetails.class, \"id\");", NO_SUCH_METHOD);
		assertRejected("operations.terminate();", NO_SUCH_METHOD);
		assertRejected("operations.erase(\"customer\", \"alice-42\", ErasureReason.of(\"why\"));", NO_SUCH_METHOD);
	}

	// ---------------------------------------------------------------- the owner keeps everything

	@Test
	void theOwnerOfTheContextStillHasEveryCapability ( ) {
		// nothing moved off the wide surface: filing the methods under an audience is all this does
		assertAccepted("owner.execute(command);");
		assertAccepted("owner.read(AccountDetails.class, \"id\");");
		assertAccepted("owner.aggregate(Account.class, Tags.of(\"account\", \"1\"));");
		assertAccepted("owner.event(domainEvent);");
		assertAccepted("owner.incoming(inboundEvent);");
		assertAccepted("owner.translate(inboundEvent);");
		assertAccepted("owner.automations();");
		assertAccepted("owner.processors();");
		assertAccepted("owner.erase(\"customer\", \"alice-42\", ErasureReason.of(\"why\"));");
		assertAccepted("owner.port(Runnable.class);");
		assertAccepted("owner.getDeployedFeatureSlices();");
		assertAccepted("owner.start(); owner.stop(); owner.terminate();");
	}

	@Test
	void aBuiltContextNarrowsToEveryAudienceWithoutACast ( ) {
		// what makes the narrowing cost a reference type and nothing else
		assertAccepted("ApplicationCapabilities<BankingEvent, BankingOutboundEvent> a = owner;");
		assertAccepted("TranslationCapability<BankingInboundEvent> t = owner;");
		assertAccepted("OperationsCapabilities o = owner;");
		assertAccepted("PrivacyCapability p = owner;");
		assertAccepted("ProvidedEventCapability<BankingEvent> e = owner;");
	}

	@Test
	void anAudienceOfTheWrongEventTypeIsStillRefused ( ) {
		assertRejected("ApplicationCapabilities<BankingInboundEvent, BankingOutboundEvent> a = owner;", INCOMPATIBLE_TYPES);
	}

	// ---------------------------------------------------------------- the shorthand a context interface can carry

	@Test
	void aContextInterfaceMayNameItsOwnApplicationSurface ( ) {
		// the idiom WHO-MAY-DO-WHAT.md documents: one interface per audience beside the context
		// interface, so a call site writes BankingApi instead of the three type arguments. The two
		// paths to ApplicationCapabilities must agree on their arguments, which is what makes a
		// mistyped alias a compile error rather than a second surface
		assertAcceptedDeclaration("""
				interface BankingApi extends ApplicationCapabilities<BankingEvent, BankingOutboundEvent> { }
				interface BankingWithApi extends BoundedContext<BankingEvent, BankingInboundEvent, BankingOutboundEvent>, BankingApi { }
				""");
	}

	@Test
	void anAliasContradictingTheContextsOwnEventTypesDoesNotCompile ( ) {
		assertRejectedDeclaration("""
				interface WrongApi extends ApplicationCapabilities<BankingInboundEvent, BankingOutboundEvent> { }
				interface BankingWithWrongApi extends BoundedContext<BankingEvent, BankingInboundEvent, BankingOutboundEvent>, WrongApi { }
				""");
	}

	// ---------------------------------------------------------------- harness

	private void assertRejected ( String statement, String expectedCode ) {
		List<Diagnostic<? extends JavaFileObject>> errors = compile(PROBE_PRELUDE + "\t\t" + statement + "\n" + PROBE_EPILOGUE);
		assertFalse(errors.isEmpty(), "javac accepted: " + statement);
		Diagnostic<? extends JavaFileObject> error = errors.get(0);
		assertEquals(expectedCode, error.getCode(),
				"javac rejected the probe, but not for the expected reason: " + error.getMessage(Locale.ENGLISH));
		assertEquals(PROBE_PRELUDE.lines().count() + 1, error.getLineNumber(),
				"javac rejected something other than the probe statement: " + error.getMessage(Locale.ENGLISH));
	}

	private void assertAccepted ( String statement ) {
		assertNoErrors(compile(PROBE_PRELUDE + "\t\t" + statement + "\n" + PROBE_EPILOGUE), statement);
	}

	private void assertAcceptedDeclaration ( String declarations ) {
		assertNoErrors(compile(withDeclarations(declarations)), declarations);
	}

	private void assertRejectedDeclaration ( String declarations ) {
		assertFalse(compile(withDeclarations(declarations)).isEmpty(), "javac accepted: " + declarations);
	}

	/**
	 * The declarations become members of the probe class, so they see the event types it declares —
	 * which is the shape being pinned: an alias interface sits beside the context interface, over the
	 * same event types.
	 */
	private static String withDeclarations ( String declarations ) {
		return PROBE_PRELUDE + "\t}\n\n" + declarations + "}\n";
	}

	private void assertNoErrors ( List<Diagnostic<? extends JavaFileObject>> errors, String probe ) {
		assertTrue(errors.isEmpty(), () -> "javac rejected: " + probe + "\n" + errors.stream()
				.map(d -> d.getLineNumber() + ": " + d.getMessage(Locale.ENGLISH))
				.collect(Collectors.joining("\n")));
	}

	private List<Diagnostic<? extends JavaFileObject>> compile ( String source ) {
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
		entries.add(new File(AllCapabilities.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath());
		String inherited = System.getProperty("java.class.path");
		if ( inherited != null && !inherited.isBlank() ) {
			entries.add(inherited);
		}
		return String.join(File.pathSeparator, entries);
	}
}
