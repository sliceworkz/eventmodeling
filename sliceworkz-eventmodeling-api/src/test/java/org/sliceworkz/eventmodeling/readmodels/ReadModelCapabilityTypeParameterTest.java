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
package org.sliceworkz.eventmodeling.readmodels;

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
 * Pins that a live read model read is typed by the class it is asked for: {@code read(AccountDetails.class,
 * id)} is an {@code AccountDetails} on {@link ReadModelCapability}, on {@link UnboundedReadModelCapability}
 * and on the command's {@code OutboundCommandContext} alike, and assigning it to anything else is a compile
 * error. And that the class argument stays constrained to the read models of the context's own event type,
 * as it was before the result was typed.
 * <p>
 * The guarantee is a compile-time one, so the only way to test it is to compile: each probe below is handed
 * to javac against this module's classes, and the test asserts which probes it rejects and which it accepts.
 * The failure this guards against is a regression to a free type parameter on the result, under which
 * {@code String s = context.read(AccountDetails.class, id)} compiles and fails as a {@code ClassCastException}
 * at the call site, on a method whose argument said exactly what would come back.
 */
public class ReadModelCapabilityTypeParameterTest {

	private static final String PROBE_PRELUDE = """
			import org.sliceworkz.eventmodeling.commands.OutboundCommandContext;
			import org.sliceworkz.eventmodeling.readmodels.ReadModel;
			import org.sliceworkz.eventmodeling.readmodels.ReadModelCapability;
			import org.sliceworkz.eventmodeling.readmodels.UnboundedReadModelCapability;

			class Probe {
				interface BankingEvent { }
				interface OrderEvent { }
				abstract static class AccountDetails implements ReadModel<BankingEvent> {
					abstract String details ( );
				}
				abstract static class AccountOverview implements ReadModel<BankingEvent> { }
				abstract static class OrderDetails implements ReadModel<OrderEvent> { }

				void probe ( ReadModelCapability<BankingEvent> context,
						UnboundedReadModelCapability<BankingEvent> unbounded,
						OutboundCommandContext<BankingEvent, Object> command,
						Class<? extends ReadModel<BankingEvent>> someReadModelClass ) {
			""";

	private static final String PROBE_EPILOGUE = """
				}
			}
			""";

	// javac's key for "incompatible types", stable across locales and versions
	private static final String INCOMPATIBLE_TYPES = "compiler.err.prob.found.req";

	// javac's key for "no suitable method found", which is how an argument is rejected on an overloaded method
	private static final String NO_APPLICABLE_METHOD = "compiler.err.cant.apply.symbols";

	// javac's key for "method cannot be applied", the same rejection on a method with one signature
	private static final String NOT_APPLICABLE = "compiler.err.cant.apply.symbol";

	@TempDir
	Path outputDirectory;

	@Test
	void aReadIsTypedByTheClassItAsksFor ( ) {
		assertAccepted("AccountDetails d = context.read(AccountDetails.class, \"id\");");
		assertAccepted("AccountDetails d = context.read(AccountDetails.class);");
		assertAccepted("var d = context.read(AccountDetails.class, \"id\"); d.details();");
	}

	@Test
	void aReadChainsWithoutACast ( ) {
		assertAccepted("String s = context.read(AccountDetails.class, \"id\").details();");
	}

	@Test
	void aReadAssignedToAnUnrelatedTypeDoesNotCompile ( ) {
		// the trap a free type parameter leaves open: inferred from the target, found out at the call site
		assertRejected("String s = context.read(AccountDetails.class, \"id\");");
	}

	@Test
	void aReadAssignedToAnotherReadModelDoesNotCompile ( ) {
		assertRejected("AccountOverview o = context.read(AccountDetails.class, \"id\");");
	}

	@Test
	void aReadModelOfAnotherEventTypeIsStillRefused ( ) {
		// what typing the result must not loosen: the class argument stays bounded by the context's event type
		assertRejected("context.read(OrderDetails.class);", NO_APPLICABLE_METHOD);
	}

	@Test
	void aReadModelClassKnownOnlyAsAWildcardStillReads ( ) {
		// the shape the published LiveModelTest base uses: the class comes from an abstract method
		assertAccepted("ReadModel<? extends BankingEvent> m = context.read(someReadModelClass, \"id\");");
		assertAccepted("Object m = context.read(someReadModelClass);");
	}

	@Test
	void theUnboundedReadIsTypedTheSameWay ( ) {
		assertAccepted("AccountDetails d = unbounded.readUnbounded(AccountDetails.class, \"id\");");
		assertRejected("String s = unbounded.readUnbounded(AccountDetails.class, \"id\");");
		assertRejected("unbounded.readUnbounded(OrderDetails.class);", NO_APPLICABLE_METHOD);
	}

	@Test
	void aCommandsAuxiliaryReadIsTypedTheSameWay ( ) {
		assertAccepted("AccountDetails d = command.read(AccountDetails.class, \"id\");");
		assertRejected("String s = command.read(AccountDetails.class, \"id\");");
		assertRejected("command.read(OrderDetails.class);", NOT_APPLICABLE);
	}

	private void assertRejected ( String statement ) {
		assertRejected(statement, INCOMPATIBLE_TYPES);
	}

	private void assertRejected ( String statement, String expectedCode ) {
		List<Diagnostic<? extends JavaFileObject>> errors = compile(statement);
		assertFalse(errors.isEmpty(), "javac accepted: " + statement);
		Diagnostic<? extends JavaFileObject> error = errors.get(0);
		assertEquals(expectedCode, error.getCode(),
				"javac rejected the probe, but not for the expected reason: " + error.getMessage(Locale.ENGLISH));
		assertEquals(probeLine(), error.getLineNumber(),
				"javac rejected something other than the probe statement: " + error.getMessage(Locale.ENGLISH));
	}

	private void assertAccepted ( String statement ) {
		List<Diagnostic<? extends JavaFileObject>> errors = compile(statement);
		assertTrue(errors.isEmpty(), () -> "javac rejected: " + statement + "\n" + errors.stream()
				.map(d -> d.getLineNumber() + ": " + d.getMessage(Locale.ENGLISH))
				.collect(Collectors.joining("\n")));
	}

	/** the line of the probe statement in the generated source, so a rejection can be pinned to it */
	private static long probeLine ( ) {
		return PROBE_PRELUDE.lines().count() + 1;
	}

	private List<Diagnostic<? extends JavaFileObject>> compile ( String statement ) {
		String source = PROBE_PRELUDE + "\t\t" + statement + "\n" + PROBE_EPILOGUE;
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

	/**
	 * The classes the probe compiles against: this module's own, plus whatever the test JVM was started with
	 * (javac follows a manifest-only jar's Class-Path, so surefire's booter jar is enough on its own).
	 */
	private static String classpath ( ) {
		List<String> entries = new ArrayList<>();
		entries.add(new File(ReadModelCapability.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath());
		String inherited = System.getProperty("java.class.path");
		if ( inherited != null && !inherited.isBlank() ) {
			entries.add(inherited);
		}
		return String.join(File.pathSeparator, entries);
	}
}
