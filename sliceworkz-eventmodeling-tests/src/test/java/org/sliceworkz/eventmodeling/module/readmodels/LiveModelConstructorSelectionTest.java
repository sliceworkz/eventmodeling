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
package org.sliceworkz.eventmodeling.module.readmodels;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;

/**
 * A live read model is constructed by its parameter types, not by their count.
 *
 * <p>Taking the first declared constructor of the right arity leaves the choice to
 * {@code getDeclaredConstructors()}, which is documented to return them in no particular order — so a
 * read model with two constructors of one arity is projected through whichever the JVM happened to list
 * first, and a suite that passes has only ever seen the order it got. {@link MockReadModel} is that
 * shape already: {@code (String, List)} beside {@code (String, ReadModelStorage)}.
 *
 * <p>These are plain {@code @Test}s: which constructor runs is a property of the framework and has
 * nothing to do with the storage behind it.
 */
public class LiveModelConstructorSelectionTest {

	@Test
	void twoConstructorsOfOneArityAreToldApartByTheirParameterTypes ( ) throws Exception {
		assertArrayEquals(
				new Class<?>[] { String.class, List.class },
				LiveModelConstructors.select(MockReadModel.class, new Object[] { "name", List.of(String.class) }).getParameterTypes());

		assertArrayEquals(
				new Class<?>[] { String.class, ReadModelStorage.class },
				LiveModelConstructors.select(MockReadModel.class, new Object[] { "name", ReadModelStorage.LOCAL }).getParameterTypes());
	}

	/** And the one that is picked really does run, rather than merely being reported. */
	@Test
	void theSelectedConstructorIsTheOneThatRuns ( ) throws Exception {
		Constructor<?> ctr = LiveModelConstructors.select(MockReadModel.class, new Object[] { "local one", ReadModelStorage.LOCAL });
		MockReadModel readModel = (MockReadModel) ctr.newInstance("local one", ReadModelStorage.LOCAL);

		assertEquals("local one", readModel.readmodelName());
		assertEquals(ReadModelStorage.LOCAL, readModel.storage());
	}

	@Test
	void theMostSpecificConstructorWinsAsItDoesInJava ( ) {
		assertArrayEquals(
				new Class<?>[] { String.class },
				LiveModelConstructors.select(Overloaded.class, new Object[] { "a string" }).getParameterTypes());

		assertArrayEquals(
				new Class<?>[] { Object.class },
				LiveModelConstructors.select(Overloaded.class, new Object[] { 42 }).getParameterTypes());
	}

	/**
	 * A tie is refused rather than decided by array order, and the message names everything needed to
	 * fix it: the read model, what was passed, and the constructors that were weighed.
	 */
	@Test
	void aGenuineTieIsRefusedByName ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> LiveModelConstructors.select(Ambiguous.class, new Object[] { "left", "right" }));

		assertTrue(e.getMessage().contains(Ambiguous.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("String, String"), "the arguments as passed: " + e.getMessage());
		assertTrue(e.getMessage().contains("Ambiguous(String, Object)"), "both candidates: " + e.getMessage());
		assertTrue(e.getMessage().contains("Ambiguous(Object, String)"), "both candidates: " + e.getMessage());
	}

	@Test
	void anArgumentNoConstructorTakesNamesTheReadModelTheArgumentsAndWhatIsOnOffer ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> LiveModelConstructors.select(Overloaded.class, new Object[] { "one", "two" }));

		assertTrue(e.getMessage().contains(Overloaded.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("String, String"), "the arguments as passed: " + e.getMessage());
		assertTrue(e.getMessage().contains("Overloaded(String)"), "and what the read model declares: " + e.getMessage());
	}

	/**
	 * A constructor the framework cannot call is no candidate however well it fits — it would be chosen
	 * and then fail inside {@code newInstance} with an {@code IllegalAccessException} naming neither the
	 * constructor nor the reason. It is named in the failure instead.
	 */
	@Test
	void aConstructorTheFrameworkCannotReachIsNotSelectedButIsExplained ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> LiveModelConstructors.select(OnlyPrivateConstructor.class, new Object[] { "a string" }));

		assertTrue(e.getMessage().contains(OnlyPrivateConstructor.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("not accessible"), "and says why, rather than 'no such constructor': " + e.getMessage());
	}

	@Test
	void aNullFitsAReferenceParameterAndNoPrimitiveOne ( ) {
		assertArrayEquals(
				new Class<?>[] { String.class },
				LiveModelConstructors.select(Primitives.class, new Object[] { (Object) null }).getParameterTypes());
	}

	/**
	 * An exact match beats a widening one. The boxed types of {@code int} and {@code long} are unrelated,
	 * so neither constructor is the more specific of the two and the tie could not be broken otherwise —
	 * yet Java resolves this without hesitating, and so does a read.
	 */
	@Test
	void anExactPrimitiveMatchBeatsAWideningOne ( ) {
		assertArrayEquals(
				new Class<?>[] { int.class },
				LiveModelConstructors.select(Primitives.class, new Object[] { 7 }).getParameterTypes());

		assertArrayEquals(
				new Class<?>[] { long.class },
				LiveModelConstructors.select(Primitives.class, new Object[] { 7L }).getParameterTypes());
	}

	/** A widening conversion still fits where nothing matches exactly, as {@code newInstance} performs it. */
	@Test
	void aWideningConversionStillFits ( ) {
		assertArrayEquals(
				new Class<?>[] { double.class },
				LiveModelConstructors.select(OnlyDouble.class, new Object[] { 7 }).getParameterTypes());
	}

	@Test
	void anOrdinaryReadModelClassHasNothingToObjectTo ( ) {
		assertNull(LiveModelConstructors.uninstantiableReason(MockReadModel.class));
		assertNull(LiveModelConstructors.uninstantiableReason(Overloaded.class));
	}

	/**
	 * What is refused up front is only what no read could fix: an abstract class or an interface
	 * registered where an implementation was meant, and a class whose every constructor is out of reach.
	 * Ambiguity is not on that list — it depends on the arguments, and {@code MockReadModel}'s two
	 * two-argument constructors take unrelated types, so no read of it is ambiguous.
	 */
	@Test
	void whatNoReadCouldEverConstructIsRefusedUpFront ( ) {
		assertNotNull(LiveModelConstructors.uninstantiableReason(ReadModel.class));
		assertNotNull(LiveModelConstructors.uninstantiableReason(AbstractReadModel.class));

		String reason = LiveModelConstructors.uninstantiableReason(OnlyPrivateConstructor.class);
		assertNotNull(reason);
		assertTrue(reason.contains("OnlyPrivateConstructor(String)"), "naming what it does declare: " + reason);
	}

	// ---- fixtures -------------------------------------------------------------------------------
	//
	// Their constructors are public where the framework is meant to reach them, and the unreachable
	// case is an explicitly private constructor: a class that is merely package-private is in the same
	// runtime package as the framework here and would be reachable, which is what lets a test's own
	// read model declare a package-private constructor and still be projected.

	private abstract static class Fixture implements ReadModel<MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
		}

	}

	public static class Overloaded extends Fixture {

		public Overloaded ( String s ) { }

		public Overloaded ( Object o ) { }

	}

	public static class Ambiguous extends Fixture {

		public Ambiguous ( String first, Object second ) { }

		public Ambiguous ( Object first, String second ) { }

	}

	public static class OnlyPrivateConstructor extends Fixture {

		private OnlyPrivateConstructor ( String s ) { }

	}

	public static class Primitives extends Fixture {

		public Primitives ( int i ) { }

		public Primitives ( long l ) { }

		public Primitives ( String s ) { }

	}

	public static class OnlyDouble extends Fixture {

		public OnlyDouble ( double d ) { }

	}

	public abstract static class AbstractReadModel extends Fixture {
	}

}
