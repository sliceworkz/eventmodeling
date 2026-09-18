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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picks the constructor a live read model is projected through, for the parameters a read supplied.
 * <p>
 * A live read model is instantiated per read — {@code read(AccountDetails.class, accountId)} — so the
 * class is registered and the arguments arrive later, which makes this the one place the two meet.
 * <p>
 * <b>The parameter <i>types</i> decide, never the parameter count alone.</b> Taking the first declared
 * constructor of the right arity loses on both halves of that: {@code getDeclaredConstructors()} is
 * documented to return its constructors in no particular order, so a read model with two constructors
 * of one arity is projected through whichever the JVM happened to list first — a choice that can differ
 * between runs of one build and between recompiles, and that no test pins because a suite that passes
 * has already seen the order it got. And an argument the chosen constructor cannot take is then reported
 * by {@code newInstance} as a bare {@code argument type mismatch}, naming neither the read model, nor
 * the parameter, nor what was passed. {@code MockReadModel} in this repository's own tests is exactly
 * that shape: {@code (String, List)} beside {@code (String, ReadModelStorage)}.
 * <p>
 * So a constructor is a candidate only when every argument fits its parameter, and where several fit
 * the most specific one wins, as it does in Java's own overload resolution — {@code (Object)} beside
 * {@code (String)} resolves to {@code (String)} for a string. What is left over is a genuine tie
 * ({@code (String, Object)} beside {@code (Object, String)} for two strings), and that is refused by
 * name rather than decided by array order.
 * <p>
 * <b>Ambiguity is refused here and not at build time</b>, deliberately, because it is a property of the
 * read and not of the class: {@code MockReadModel}'s two two-argument constructors take unrelated types,
 * so every actual read names one of them, and a build-time rejection of same-arity constructors would
 * refuse a read model that can never be read ambiguously. What <i>is</i> a property of the class —
 * being abstract, or declaring no constructor this framework can reach — is argument-independent and is
 * refused at build time; see {@link #uninstantiableReason}.
 * <p>
 * <b>Accessibility is part of the match</b>, not an afterthought: a constructor this framework cannot
 * call is no use however well its parameters fit, so it is excluded from the candidates and named in the
 * failure instead of being selected and then failing inside {@code newInstance} with an
 * {@code IllegalAccessException} that says neither which constructor nor why. Nothing is made accessible
 * — a read model is instantiated by the framework, so a constructor it is meant to use is one it can
 * see.
 */
public final class LiveModelConstructors {

	/** Widening primitive conversions (JLS 5.1.2), which {@code Constructor.newInstance} performs too. */
	private static final Map<Class<?>, Set<Class<?>>> WIDENS_TO = Map.of(
			byte.class,  Set.of(short.class, int.class, long.class, float.class, double.class),
			short.class, Set.of(int.class, long.class, float.class, double.class),
			char.class,  Set.of(int.class, long.class, float.class, double.class),
			int.class,   Set.of(long.class, float.class, double.class),
			long.class,  Set.of(float.class, double.class),
			float.class, Set.of(double.class));

	private static final Map<Class<?>, Class<?>> BOXED = Map.of(
			boolean.class, Boolean.class,
			byte.class,    Byte.class,
			char.class,    Character.class,
			short.class,   Short.class,
			int.class,     Integer.class,
			long.class,    Long.class,
			float.class,   Float.class,
			double.class,  Double.class);

	private LiveModelConstructors ( ) { }

	/**
	 * Why this class can never be projected as a live read model, or {@code null} when it can.
	 * <p>
	 * Only what holds whatever a read passes: the class is abstract (an interface or an abstract base
	 * registered where an implementation was meant), or it declares no constructor reachable from here.
	 * Either is dead on arrival — every read of it fails — so it is worth the build rather than the
	 * first read, in the same spirit as a read model registered without a mode. Whether the arguments
	 * of a particular read fit is not knowable here and is answered by {@link #select}.
	 */
	public static String uninstantiableReason ( Class<?> readModelClass ) {
		if ( readModelClass.isInterface() ) {
			return "is an interface";
		}
		if ( Modifier.isAbstract(readModelClass.getModifiers()) ) {
			return "is abstract";
		}
		if ( accessibleConstructorsOf(readModelClass).isEmpty() ) {
			return "declares no constructor this framework can call: " + declaredConstructorsOf(readModelClass);
		}
		return null;
	}

	/**
	 * The constructor to project {@code readModelClass} through for {@code args}.
	 *
	 * @throws IllegalArgumentException when no reachable constructor accepts the arguments, or when
	 *         several do and none of them is more specific than the rest. Both messages name the read
	 *         model, the argument types as passed, and the constructors that were weighed.
	 */
	public static Constructor<?> select ( Class<?> readModelClass, Object[] args ) {
		Object[] arguments = args == null ? new Object[0] : args;

		List<Constructor<?>> applicable = accessibleConstructorsOf(readModelClass).stream()
				.filter(ctr -> applicable(ctr, arguments))
				.toList();

		if ( applicable.isEmpty() ) {
			throw new IllegalArgumentException(noSuchConstructor(readModelClass, arguments));
		}
		if ( applicable.size() == 1 ) {
			return applicable.get(0);
		}

		// an exact match on every argument's runtime type beats every conversion, which is what keeps
		// (int) from tying with (long) for an Integer -- their boxed types are unrelated, so neither is
		// the more specific of the two and the tie below could not be broken
		List<Constructor<?>> exact = applicable.stream().filter(ctr -> exactlyMatches(ctr, arguments)).toList();
		if ( exact.size() == 1 ) {
			return exact.get(0);
		}

		List<Constructor<?>> candidates = exact.size() > 1 ? exact : applicable;
		List<Constructor<?>> mostSpecific = candidates.stream()
				.filter(ctr -> candidates.stream().allMatch(other -> atLeastAsSpecificAs(ctr, other)))
				.toList();
		if ( mostSpecific.size() == 1 ) {
			return mostSpecific.get(0);
		}

		throw new IllegalArgumentException(ambiguous(readModelClass, arguments, candidates));
	}

	private static String noSuchConstructor ( Class<?> readModelClass, Object[] args ) {
		String message = "no constructor of %s takes the parameters this read passed: (%s). Declared: %s"
				.formatted(readModelClass.getName(), render(args), declaredConstructorsOf(readModelClass));

		// the shape worth calling out on its own: a constructor that fits and cannot be reached is a
		// missing modifier, not a missing constructor, and the list above does not say so by itself
		boolean fitsButUnreachable = Arrays.stream(readModelClass.getDeclaredConstructors())
				.anyMatch(ctr -> !canReach(ctr) && applicable(ctr, args));
		if ( fitsButUnreachable ) {
			message += " -- one of them fits, but is not accessible from the framework: make it public";
		}
		return message;
	}

	private static String ambiguous ( Class<?> readModelClass, Object[] args, List<Constructor<?>> candidates ) {
		return ("the parameters this read passed to %s -- (%s) -- fit more than one of its constructors and none of "
				+ "them is more specific than the rest: %s. Which one runs would be decided by the order the JVM "
				+ "happens to list them in, so it is refused instead. Give the read model one constructor for this "
				+ "shape, or pass arguments that name one of them.")
				.formatted(readModelClass.getName(), render(args), render(candidates));
	}

	private static List<Constructor<?>> accessibleConstructorsOf ( Class<?> readModelClass ) {
		// no order is imposed here: the selection below either ends on one constructor or on a refusal,
		// so nothing it decides depends on the order, and only the messages -- which sort for themselves
		// -- would notice that getDeclaredConstructors() promises none
		return Arrays.stream(readModelClass.getDeclaredConstructors())
				.filter(LiveModelConstructors::canReach)
				.toList();
	}

	/**
	 * Whether this framework can invoke the constructor, judged from here — which is where
	 * {@code newInstance} is reached from, so it is the accessibility that decides.
	 */
	private static boolean canReach ( Constructor<?> ctr ) {
		return ctr.canAccess(null);
	}

	private static boolean applicable ( Constructor<?> ctr, Object[] args ) {
		Class<?>[] params = ctr.getParameterTypes();
		if ( params.length != args.length ) {
			return false;
		}
		for ( int i = 0; i < params.length; i++ ) {
			if ( !accepts(params[i], args[i]) ) {
				return false;
			}
		}
		return true;
	}

	private static boolean accepts ( Class<?> param, Object arg ) {
		if ( arg == null ) {
			// a null is every reference type and no primitive, exactly as newInstance reads it
			return !param.isPrimitive();
		}
		if ( !param.isPrimitive() ) {
			return param.isInstance(arg);
		}
		Class<?> argPrimitive = primitiveOf(arg.getClass());
		return argPrimitive != null
				&& ( argPrimitive == param || WIDENS_TO.getOrDefault(argPrimitive, Set.of()).contains(param) );
	}

	private static boolean exactlyMatches ( Constructor<?> ctr, Object[] args ) {
		Class<?>[] params = ctr.getParameterTypes();
		for ( int i = 0; i < params.length; i++ ) {
			if ( args[i] == null ) {
				continue;   // says nothing either way, so it cannot break a tie
			}
			if ( boxed(params[i]) != args[i].getClass() ) {
				return false;
			}
		}
		return true;
	}

	/** As in Java's own overload resolution: every parameter of {@code ctr} fits where {@code other}'s does. */
	private static boolean atLeastAsSpecificAs ( Constructor<?> ctr, Constructor<?> other ) {
		Class<?>[] mine = ctr.getParameterTypes();
		Class<?>[] theirs = other.getParameterTypes();
		for ( int i = 0; i < mine.length; i++ ) {
			if ( !boxed(theirs[i]).isAssignableFrom(boxed(mine[i])) ) {
				return false;
			}
		}
		return true;
	}

	private static Class<?> boxed ( Class<?> type ) {
		return BOXED.getOrDefault(type, type);
	}

	private static Class<?> primitiveOf ( Class<?> wrapper ) {
		return BOXED.entrySet().stream()
				.filter(e -> e.getValue() == wrapper)
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
	}

	private static String declaredConstructorsOf ( Class<?> readModelClass ) {
		return render(Arrays.asList(readModelClass.getDeclaredConstructors()));
	}

	/** Sorted, so a message reads the same on every run whatever order the JVM listed the constructors in. */
	private static String render ( List<Constructor<?>> constructors ) {
		return constructors.stream()
				.map(LiveModelConstructors::render)
				.sorted()
				.collect(Collectors.joining(", "));
	}

	private static String render ( Constructor<?> ctr ) {
		String modifiers = Modifier.toString(ctr.getModifiers() & Modifier.constructorModifiers());
		return "%s%s(%s)".formatted(
				modifiers.isEmpty() ? "" : modifiers + " ",
				ctr.getDeclaringClass().getSimpleName(),
				Arrays.stream(ctr.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(", ")));
	}

	/** The argument types as passed, which is what a caller compares against the constructors listed beside it. */
	private static String render ( Object[] args ) {
		return Arrays.stream(args)
				.map(arg -> arg == null ? "null" : arg.getClass().getSimpleName())
				.collect(Collectors.joining(", "));
	}

}
