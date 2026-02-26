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

import java.util.function.Predicate;

import org.sliceworkz.eventmodeling.slices.Slice;

public interface FeaturesSpecification<C extends BoundedContext<?,?,?>> {

	FeaturesSpecification<C> rootPackage(Package rootPackage);

	FeaturesSpecification<C> filter (Predicate<Slice<C>> filter);

	FeaturesSpecification<C> enableCommands ( );
	FeaturesSpecification<C> enableCommands ( boolean enableCommands );
	FeaturesSpecification<C> disableCommands ( );

	FeaturesSpecification<C> enableQueries( );
	FeaturesSpecification<C> enableQueries( boolean enableQueries );
	FeaturesSpecification<C> disableQueries( );

	FeaturesSpecification<C> enableAutomations ( );
	FeaturesSpecification<C> enableAutomations ( boolean enableAutomations);
	FeaturesSpecification<C> disableAutomations ( );

	FeaturesSpecification<C> enableProjections ( );
	FeaturesSpecification<C> enableProjections ( boolean enableProjections );
	FeaturesSpecification<C> disableProjections ( );

	BoundedContextBuilder<C> done ( );

}
