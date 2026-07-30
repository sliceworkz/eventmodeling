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
package org.sliceworkz.eventmodeling.slices;

/**
 * The four independently deployable facets of a feature slice.
 * <p>
 * A slice configures each facet separately ({@link Slice#configureCommand}, {@link Slice#configureQuery},
 * {@link Slice#configureAutomation}, {@link Slice#configureProjection}) and a deployment decides which
 * of them it runs, through
 * {@link org.sliceworkz.eventmodeling.boundedcontext.FeaturesSpecification#disableQueries()} and its
 * siblings. That is what lets the parts of one slice run in different places: the read model answering
 * queries, the projector keeping a shared read model up to date, and the automation driving it forward
 * need not live on the same instance.
 * <p>
 * Two facts are needed to know where a component actually runs, and they are reported separately:
 * the aspect a component was registered in, on
 * {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.SliceMember}, and the aspects
 * a deployment runs, on
 * {@link org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent.BoundedContextStarting}. A
 * component runs on an instance when that instance deploys its slice and runs its aspect.
 */
public enum Aspect {

	/** Commands: the state-change side of a slice, deployed where commands are accepted. */
	COMMAND,

	/** Queries: read models projected on demand to answer a request, deployed where queries are served. */
	QUERY,

	/** Automations: the processors that turn a todo list into further commands. */
	AUTOMATION,

	/** Projections: the processors that keep eventually consistent read models up to date. */
	PROJECTION

}
