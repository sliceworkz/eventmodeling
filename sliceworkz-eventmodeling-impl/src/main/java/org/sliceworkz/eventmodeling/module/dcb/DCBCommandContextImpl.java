/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.module.dcb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;

public class DCBCommandContextImpl<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> implements CommandContext<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> {
	
	private String boundedContext;
	private EventStream<CONSUMED_EVENT_TYPE> queryEventStream;
	private EventStream<PRODUCED_EVENT_TYPE> targetEventStream;
	private Tracing tracing;
	private List<DecisionModel<CONSUMED_EVENT_TYPE>> decisionModels = new ArrayList<>();
	private ReadModelModule<CONSUMED_EVENT_TYPE> readModelModule;
	
	private Projector<CONSUMED_EVENT_TYPE> projector;
	private ProjectorMetrics projectorMetrics;
	
	private boolean decisionModelsDetermined = false;
	
	public DCBCommandContextImpl ( String boundedContext, ReadModelModule<CONSUMED_EVENT_TYPE> readModelModule, EventStream<CONSUMED_EVENT_TYPE> queryEventStream, EventStream<PRODUCED_EVENT_TYPE> targetEventStream, Tracing tracing ) {
		this.boundedContext = boundedContext;
		this.readModelModule = readModelModule;
		this.queryEventStream = queryEventStream;
		this.targetEventStream = targetEventStream;
		this.tracing = tracing;
	}
	
	public CommandResult<CONSUMED_EVENT_TYPE,PRODUCED_EVENT_TYPE> noDecisionModels ( ) {
		validateNoDuplicateDecisionModels();
		// run without any decision models
		return executeDecisionModels();
	}	
	
	@SafeVarargs
	public final CommandResult<CONSUMED_EVENT_TYPE,PRODUCED_EVENT_TYPE> decisionModels ( DecisionModel<CONSUMED_EVENT_TYPE>... p ) {
		validateNoDuplicateDecisionModels();
		decisionModels.addAll(Arrays.asList(p));
		return executeDecisionModels();
	}
	
	private void validateNoDuplicateDecisionModels ( ) {
		if ( decisionModelsDetermined ) {
			throw new RuntimeException("decision models can only be selected once in a command context");
		}
		decisionModelsDetermined = true;
	}

	private CommandResult<CONSUMED_EVENT_TYPE,PRODUCED_EVENT_TYPE> executeDecisionModels ( ) {

		EventQuery combinedQuery = EventQuery.matchNone();
		
		Optional<EventReference> lastEventReference = Optional.empty();
		
		// loop over all decisionmodels
		for ( DecisionModel<CONSUMED_EVENT_TYPE> p: decisionModels ) {
			// combine queries into one that fetches all
			combinedQuery = combinedQuery.combineWith(p.eventQuery());
		}

		// execute combined query for all decisionmodels

		final var finalCombinedQuery = combinedQuery;
		
		this.projector = Projector.from(queryEventStream).towards(new Projection<>() {

			@Override
			public void when(Event<CONSUMED_EVENT_TYPE> event) {
				offerEventToDecisionModels(event, decisionModels);
			}

			@Override
			public EventQuery eventQuery() {
				return finalCombinedQuery;
			}
			
		}).build();
		
		if  ( ! decisionModels.isEmpty()  ) {
			projectorMetrics = this.projector.run();
			lastEventReference = Optional.ofNullable(projectorMetrics.lastEventReference());
		}
		
		return new CommandResultImpl<>(boundedContext, targetEventStream.id(), tracing, combinedQuery, lastEventReference);
	}
	
	private Event<? extends CONSUMED_EVENT_TYPE> offerEventToDecisionModels ( Event<CONSUMED_EVENT_TYPE> e, List<DecisionModel<CONSUMED_EVENT_TYPE>> decisionModels ) {
		for ( DecisionModel<CONSUMED_EVENT_TYPE> decisionModel: decisionModels ) {
			offerEventToDecisionModel(e, decisionModel);
		}
		return e;
	}

	private void offerEventToDecisionModel ( Event<CONSUMED_EVENT_TYPE> e, DecisionModel<CONSUMED_EVENT_TYPE> p ) {
		if ( p.eventQuery().matches(e) ) {
			p.when(e);
		}
	}

	public Tracing tracing ( ) {
		return tracing;
	}
	
	public Projector<CONSUMED_EVENT_TYPE> projector ( ) {
		return projector;
	}
	
	public ProjectorMetrics projectorMetrics ( ) {
		return projectorMetrics == null?ProjectorMetrics.empty():projectorMetrics;
	}

	@Override
	public Optional<EventReference> getEventReference(EventId eventId) {
		return queryEventStream.queryReference(eventId);
	}

	@Override
	public <T> T read(Class<? extends ReadModel<? extends CONSUMED_EVENT_TYPE>> readModelClass, Object... constructorParams) {
		return readModelModule.liveModel(readModelClass, tracing, constructorParams);
	}

}
