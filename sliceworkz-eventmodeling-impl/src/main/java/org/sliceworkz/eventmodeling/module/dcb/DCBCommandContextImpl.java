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
import org.sliceworkz.eventstore.query.MergedEventQueries;
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
	 * Per-decision-model projection result emitted as a {@code DecisionModelProjected} bounded-context
	 * event. {@code eventsStreamed}/{@code queriesDone}/{@code durationMs}/{@code until} describe the
	 * physical read the model was projected from (shared by all models that were read together via a
	 * single merged query); {@code eventsHandled} is the number of those events relevant to (handled by)
	 * this particular decision model.
	 */
	public record DecisionModelProjection ( Class<?> decisionModelClass, long durationMs, long queriesDone, long eventsStreamed, long eventsHandled, EventReference until ) { }
	
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

		// Partition the decision models. Models with a savepoint (initQuery) keep their own projector so
		// the Projector handles their initQuery/eventQuery cursor management; their eventQuery replay must
		// start after the savepoint and therefore cannot be merged with other reads. Plain models (no
		// initQuery) can have their eventQueries reduced by the event store into the minimal set of merged
		// physical queries and projected together through a single composite read per merged query.
		List<DecisionModel<CONSUMED_EVENT_TYPE>> savepointModels = new ArrayList<>();
		List<DecisionModel<CONSUMED_EVENT_TYPE>> plainModels = new ArrayList<>();
		for ( DecisionModel<CONSUMED_EVENT_TYPE> p: decisionModels ) {
			EventQuery initQuery = p.initQuery();
			if ( initQuery != null && !initQuery.isMatchNone() ) {
				savepointModels.add(p);
			} else {
				plainModels.add(p);
			}
		}

		List<EventQuery> plainQueries = new ArrayList<>();
		for ( DecisionModel<CONSUMED_EVENT_TYPE> p: plainModels ) {
			plainQueries.add(p.eventQuery());
		}
		MergedEventQueries mergedPlainQueries = EventQuery.merge(plainQueries);

		// Number of physical reads that will actually be executed against the store: one per merged plain
		// query plus one per savepoint model.
		int physicalReads = mergedPlainQueries.mergedCount() + savepointModels.size();

		// The filter to pin the boundary with. The pin has to name an instant at or after the newest event
		// ANY of the reads cares about, and with a savepoint model there is no way to know up front what
		// that is: the Projector bounds the initQuery too, so pinning before the newest savepoint would
		// initialise the model from a stale one, and a model is free to derive its eventQuery from what the
		// initQuery hands it — a query nobody can name before that read has happened. So a command with a
		// savepoint model pins at the newest event in the stream.
		// For the plain-only case the narrow union is kept: the newest event matching any single model's
		// filter is by definition no later than the newest event matching their union, so both pins read
		// exactly the same events and only the reference lands elsewhere. Match-all is not used there
		// because it would deserialize whichever event happens to be newest in the stream, relevant or not.
		EventQuery boundaryQuery;
		if ( savepointModels.isEmpty() ) {
			boundaryQuery = union(plainQueries);
		} else {
			boundaryQuery = EventQuery.matchAll();
		}

		// A single physical read is taken atomically by the store, so its own most-recent reference is a
		// sound optimistic-lock boundary and no extra boundary query is needed. Two or more reads happen
		// sequentially and are NOT atomic as a group: an append matching one model's filter can slip in
		// between two reads and, because a later read advances the cursor past it, escape the lock check.
		// To keep the lock sound we pin a single consistency boundary up front and bound every read to it,
		// so the lock reference covers all reads.
		// With <= 1 physical read this window does not exist and the boundary query is skipped.
		EventReference boundary = ( physicalReads > 1 ) ? pinBoundary(boundaryQuery) : null;

		ProjectorMetrics accumulatedMetrics = ProjectorMetrics.empty();
		EventReference singleReadMostRecent = null;

		// project the plain models: one composite read per merged query, dispatching each event to the
		// plain models whose own eventQuery matches it
		for ( EventQuery mergedQuery: mergedPlainQueries.mergedQueries() ) {
			List<DecisionModel<CONSUMED_EVENT_TYPE>> modelsForRead = new ArrayList<>();
			for ( DecisionModel<CONSUMED_EVENT_TYPE> p: plainModels ) {
				if ( mergedQuery.equals(mergedPlainQueries.mergedFor(p.eventQuery())) ) {
					modelsForRead.add(p);
				}
			}
			CompositeDecisionModel<CONSUMED_EVENT_TYPE> composite = new CompositeDecisionModel<>(mergedQuery, modelsForRead);
			long start = System.currentTimeMillis();
			Projector<CONSUMED_EVENT_TYPE> projector = Projector.from(queryEventStream).towards(composite).build();
			ProjectorMetrics metrics = ( boundary == null ) ? projector.run() : projector.runUntil(boundary);
			long durationMs = System.currentTimeMillis() - start;
			accumulatedMetrics = accumulatedMetrics.add(metrics);
			singleReadMostRecent = metrics.mostRecentEventReference();
			for ( int i = 0; i < modelsForRead.size(); i++ ) {
				decisionModelProjections.add(new DecisionModelProjection(
						modelsForRead.get(i).getClass(), durationMs, metrics.queriesDone(),
						metrics.eventsStreamed(), composite.handledBy(i), metrics.mostRecentEventReference()));
			}
		}

		// project each savepoint model through its own projector so its initQuery savepoint is honoured
		for ( DecisionModel<CONSUMED_EVENT_TYPE> p: savepointModels ) {
			long start = System.currentTimeMillis();
			Projector<CONSUMED_EVENT_TYPE> projector = Projector.from(queryEventStream).towards(p).build();
			ProjectorMetrics metrics = ( boundary == null ) ? projector.run() : projector.runUntil(boundary);
			long durationMs = System.currentTimeMillis() - start;
			accumulatedMetrics = accumulatedMetrics.add(metrics);
			singleReadMostRecent = metrics.mostRecentEventReference();
			decisionModelProjections.add(new DecisionModelProjection(
					p.getClass(), durationMs, metrics.queriesDone(),
					metrics.eventsStreamed(), metrics.eventsHandled(), metrics.mostRecentEventReference()));
		}

		// the lock reference: the pinned boundary when several reads were performed, otherwise the single
		// read's most-recent reference (null when no decision models / no matching events were read)
		EventReference lastEventReference = ( boundary != null ) ? boundary : singleReadMostRecent;

		projectorMetrics = accumulatedMetrics;

		// The optimistic-lock filter: the union of the eventQueries the models were ACTUALLY read with,
		// which is why it is built here and not before the reads. A savepoint model only learns its query
		// once its initQuery has run — the Projector calls eventQuery() after handing it those events — so
		// a union taken up front would describe a query that was never executed. Where such a model starts
		// out matchNone, that union is matchNone, which is precisely AppendCriteria.none(): the command
		// would decide on facts it never locked and append with no consistency check at all.
		// Every event matching this filter up to the boundary was read, by the same query under the same
		// boundary, so "nothing matching appeared after the reference" is exactly the right check.
		List<EventQuery> readQueries = new ArrayList<>();
		for ( DecisionModel<CONSUMED_EVENT_TYPE> p: decisionModels ) {
			readQueries.add(p.eventQuery());
		}
		EventQuery lockQuery = union(readQueries);

		commandResult = new CommandResultImpl<>(boundedContext, targetEventStream.id(), tracing, lockQuery.filter(), lastEventReference);
		return commandResult;
	}

	/**
	 * The union of the given queries, or match-none when there are none to combine.
	 */
	private static EventQuery union ( List<EventQuery> queries ) {
		EventQuery combined = null;
		for ( EventQuery query: queries ) {
			combined = ( combined == null ) ? query : combined.combineWith(query);
		}
		return ( combined == null ) ? EventQuery.matchNone() : combined;
	}

	/**
	 * Pins the optimistic-lock boundary for a multi-read command: the reference of the most recent event
	 * currently matching everything the command is about to read — the decision models' eventQueries plus
	 * the savepoint models' initQueries — or {@code null} when no such event exists (in which case an
	 * empty expected reference combined with the lock filter still rejects any concurrently appended
	 * matching event). Read once, up front, so all subsequent reads share it.
	 */
	private EventReference pinBoundary ( EventQuery boundaryQuery ) {
		return queryEventStream.query(boundaryQuery.backwards().limit(1))
				.map(Event::reference)
				.findFirst()
				.orElse(null);
	}

	/**
	 * A {@link Projection} over a merged eventQuery that dispatches each streamed event to the plain
	 * decision models whose own eventQuery matches it, recording per-model how many events each handled.
	 * Used to project several merged plain decision models from a single physical read.
	 */
	private static final class CompositeDecisionModel<E> implements org.sliceworkz.eventstore.projection.Projection<E> {

		private final EventQuery mergedQuery;
		private final List<DecisionModel<E>> models;
		private final long[] handled;

		CompositeDecisionModel ( EventQuery mergedQuery, List<DecisionModel<E>> models ) {
			this.mergedQuery = mergedQuery;
			this.models = models;
			this.handled = new long[models.size()];
		}

		@Override
		public EventQuery eventQuery ( ) {
			return mergedQuery;
		}

		@Override
		public void when ( Event<E> event ) {
			for ( int i = 0; i < models.size(); i++ ) {
				DecisionModel<E> model = models.get(i);
				if ( model.eventQuery().matches(event) ) {
					model.when(event);
					handled[i]++;
				}
			}
		}

		long handledBy ( int i ) {
			return handled[i];
		}
	}

	/**
	 * The result of the command that ran against this context.
	 * <p>
	 * A command builds it by choosing what it decides on — {@code context.decisionModels(...)}, or
	 * {@code context.noDecisionModels()} when it decides on nothing — and a command that does neither
	 * never produces one. That is a mistake in the command rather than a state to carry on from: there
	 * is no consistency boundary to append under and nothing to append.
	 */
	public CommandResultImpl<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> getCommandResult ( ) {
		if ( commandResult == null ) {
			throw new IllegalStateException(
					"command '%s' did not select its decision models: call context.decisionModels(...) with what it decides on, or context.noDecisionModels() when it decides on nothing - a command has to do one of the two, since that is what produces the CommandResult its events are raised on"
						.formatted(tracing.command()));
		}
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
