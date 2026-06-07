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
package org.sliceworkz.eventmodeling.module.dcb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
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
	
	private ProjectorMetrics projectorMetrics;
	private CommandResultImpl<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> commandResult;

	private final List<DecisionModelProjection> decisionModelProjections = new ArrayList<>();

	private boolean decisionModelsDetermined = false;

	/**
	 * Per-decision-model projection result: the decision model's class, the wall-clock duration of its
	 * projection and the {@link ProjectorMetrics} of its individual projector run. Used to emit a
	 * {@code DecisionModelProjected} bounded-context event per decision model.
	 */
	public record DecisionModelProjection ( Class<?> decisionModelClass, long durationMs, ProjectorMetrics metrics ) { }
	
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

		EventQuery combinedQuery = null;

		// loop over all decisionmodels
		for ( DecisionModel<CONSUMED_EVENT_TYPE> p: decisionModels ) {
			// combine queries into one that fetches all
			combinedQuery = (combinedQuery==null)?p.eventQuery():combinedQuery.combineWith(p.eventQuery());
		}

		if ( combinedQuery == null ) {
			combinedQuery = EventQuery.matchNone();
		}

		// Number of physical eventQuery reads that will be executed against the event store. Today each
		// decision model is projected with its own read, so this equals the number of decision models.
		// Once the event store can reduce several decision-model eventQueries into a smaller set of
		// merged physical queries, derive this from that reduced set (ideally 1) instead.
		int physicalEventQueries = decisionModels.size();

		// A single physical read is taken atomically by the store, so its own most-recent reference is a
		// sound optimistic-lock boundary and no extra boundary query is needed. Two or more reads happen
		// sequentially and are NOT atomic as a group: an append matching one model's filter can slip in
		// between two reads and, because a later read advances the cursor past it, escape the lock check.
		// To keep the lock sound we pin a single consistency boundary up front (the most recent event
		// matching the combined filter) and bound every read to it, so the lock reference covers all
		// reads. With <= 1 physical read this window does not exist and the boundary query is skipped.
		EventReference boundary = ( physicalEventQueries > 1 ) ? pinBoundary(combinedQuery) : null;

		// run a separate projector for each decision model so that each model's
		// initQuery (if present) and eventQuery are handled independently with
		// correct cursor management by the Projector

		EventReference lastEventReference = null;

		ProjectorMetrics accumulatedMetrics = ProjectorMetrics.empty();

		if  ( ! decisionModels.isEmpty()  ) {
			for ( DecisionModel<CONSUMED_EVENT_TYPE> p: decisionModels ) {
				long modelStart = System.currentTimeMillis();
				Projector<CONSUMED_EVENT_TYPE> modelProjector = Projector.from(queryEventStream).towards(p).build();
				// bound every read to the pinned boundary so all models share one consistency position
				ProjectorMetrics metrics = ( boundary == null ) ? modelProjector.run() : modelProjector.runUntil(boundary);
				long modelDurationMs = System.currentTimeMillis() - modelStart;
				decisionModelProjections.add(new DecisionModelProjection(p.getClass(), modelDurationMs, metrics));
				accumulatedMetrics = accumulatedMetrics.add(metrics);
				if ( boundary == null && metrics.mostRecentEventReference() != null ) {
					if ( lastEventReference == null || metrics.mostRecentEventReference().happenedAfter(lastEventReference) ) {
						lastEventReference = metrics.mostRecentEventReference();
					}
				}
			}
		}

		// when a boundary was pinned it is the single, sound lock reference shared by all reads
		if ( boundary != null ) {
			lastEventReference = boundary;
		}

		projectorMetrics = accumulatedMetrics;

		commandResult = new CommandResultImpl<>(boundedContext, targetEventStream.id(), tracing, combinedQuery.filter(), lastEventReference);
		return commandResult;
	}

	/**
	 * Pins the optimistic-lock boundary for a multi-read command: the reference of the most recent event
	 * currently matching the combined decision-model filter, or {@code null} when no such event exists
	 * (in which case an empty expected reference combined with the filter still rejects any concurrently
	 * appended matching event). Read once, up front, so all subsequent per-model reads share it.
	 */
	private EventReference pinBoundary ( EventQuery combinedQuery ) {
		return queryEventStream.query(combinedQuery.backwards().limit(1))
				.map(Event::reference)
				.findFirst()
				.orElse(null);
	}

	public CommandResultImpl<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> getCommandResult ( ) {
		return commandResult;
	}

	public Tracing tracing ( ) {
		return tracing;
	}
	
	public ProjectorMetrics projectorMetrics ( ) {
		return projectorMetrics == null?ProjectorMetrics.empty():projectorMetrics;
	}

	/**
	 * @return the per-decision-model projection results captured during command execution, in the
	 *         order the decision models were projected. Empty when the command used no decision models.
	 */
	public List<DecisionModelProjection> decisionModelProjections ( ) {
		return decisionModelProjections;
	}

	@Override
	public <T> T read(Class<? extends ReadModel<? extends CONSUMED_EVENT_TYPE>> readModelClass, Object... constructorParams) {
		return readModelModule.liveModel(readModelClass, tracing, constructorParams);
	}

}
