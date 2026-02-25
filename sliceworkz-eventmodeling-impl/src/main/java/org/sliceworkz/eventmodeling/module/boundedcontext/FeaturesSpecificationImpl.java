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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.FeaturesSpecification;
import org.sliceworkz.eventmodeling.slices.FeatureSliceConfiguration;

public class FeaturesSpecificationImpl<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> implements FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	private Package rootPackage;
	private Predicate<FeatureSliceConfiguration<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> filter = (slice) -> true;
	private BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> boundedContextBuilder;
	private boolean deployCommands = true;
	private boolean deployQueries = true;
	private boolean deployAutomations = true;

	public FeaturesSpecificationImpl ( BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> boundedContextBuilder ) {
		this.boundedContextBuilder = boundedContextBuilder;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> rootPackage(Package rootPackage) {
		this.rootPackage = rootPackage;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> filter(
			Predicate<FeatureSliceConfiguration<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> filter) {
		this.filter = filter;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> enableCommands() {
		deployCommands = true;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> enableCommands(boolean enableCommands) {
		deployCommands = enableCommands;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> disableCommands() {
		deployCommands = false;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> enableQueries() {
		deployQueries = true;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> enableQueries(boolean enableQueries) {
		deployQueries = enableQueries;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> disableQueries() {
		deployQueries = false;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> enableAutomations() {
		deployAutomations = true;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> enableAutomations(boolean enableAutomations) {
		deployAutomations = enableAutomations;
		return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> disableAutomations() {
		deployAutomations = false;
		return this;
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> done() {
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
	Predicate<FeatureSliceConfiguration<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> filter ( ) {
		return filter;
	}
}
