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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.util.function.Predicate;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.FeaturesSpecification;
import org.sliceworkz.eventmodeling.slices.Slice;

public class FeaturesSpecificationImpl<C extends BoundedContext<?,?,?>> implements FeaturesSpecification<C> {

	private Package rootPackage;
	private Predicate<Slice<C>> filter = (slice) -> true;
	private BoundedContextBuilder<C> boundedContextBuilder;
	private boolean deployCommands = true;
	private boolean deployQueries = true;
	private boolean deployAutomations = true;
	private boolean deployProjections = true;

	public FeaturesSpecificationImpl ( BoundedContextBuilder<C> boundedContextBuilder ) {
		this.boundedContextBuilder = boundedContextBuilder;
	}

	@Override
	public FeaturesSpecification<C> rootPackage(Package rootPackage) {
		this.rootPackage = rootPackage;
		return this;
	}

	@Override
	public FeaturesSpecification<C> filter(Predicate<Slice<C>> filter) {
		this.filter = filter;
		return this;
	}

	@Override
	public FeaturesSpecification<C> enableCommands() {
		deployCommands = true;
		return this;
	}

	@Override
	public FeaturesSpecification<C> enableCommands(boolean enableCommands) {
		deployCommands = enableCommands;
		return this;
	}

	@Override
	public FeaturesSpecification<C> disableCommands() {
		deployCommands = false;
		return this;
	}

	@Override
	public FeaturesSpecification<C> enableQueries() {
		deployQueries = true;
		return this;
	}

	@Override
	public FeaturesSpecification<C> enableQueries(boolean enableQueries) {
		deployQueries = enableQueries;
		return this;
	}

	@Override
	public FeaturesSpecification<C> disableQueries() {
		deployQueries = false;
		return this;
	}

	@Override
	public FeaturesSpecification<C> enableAutomations() {
		deployAutomations = true;
		return this;
	}

	@Override
	public FeaturesSpecification<C> enableAutomations(boolean enableAutomations) {
		deployAutomations = enableAutomations;
		return this;
	}

	@Override
	public FeaturesSpecification<C> disableAutomations() {
		deployAutomations = false;
		return this;
	}

	@Override
	public FeaturesSpecification<C> enableProjections() {
		deployProjections = true;
		return this;
	}

	@Override
	public FeaturesSpecification<C> enableProjections(boolean enableProjections) {
		deployProjections = enableProjections;
		return this;
	}

	@Override
	public FeaturesSpecification<C> disableProjections() {
		deployProjections = false;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> done() {
		return boundedContextBuilder;
	}

	Package rootPackage ( ) {
		return rootPackage;
	}

	boolean mustDeployCommands ( ) {
		return deployCommands;
	}

	boolean mustDeployQueries( ) {
		return deployQueries;
	}

	boolean mustDeployAutomations( ) {
		return deployAutomations;
	}

	boolean mustDeployProjections( ) {
		return deployProjections;
	}

	Predicate<Slice<C>> filter ( ) {
		return filter;
	}
}
