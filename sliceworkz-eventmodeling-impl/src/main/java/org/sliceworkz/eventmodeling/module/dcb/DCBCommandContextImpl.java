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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.sliceworkz.eventstore.query.EventFilter;
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
		// initQuery) have their eventQueries reduced to the minimal set of merged physical queries (see
		// merge below) and are projected together through a single composite read per merged query.
		List<DecisionModel<CONSUMED_EVENT_TYPE>> savepointModels = new ArrayList<>();
		List<DecisionModel<CONSUMED_EVENT_TYPE>> plainModels = new ArrayList<>();
		// The savepoint queries as they are about to be read, kept for the lock filter below. Captured
		// here rather than asked for again after the reads, deliberately the opposite of eventQuery():
		// this is the query the Projector is handed, so the filter that is locked on is the filter the
		// savepoint was actually found with.
		List<EventFilter> savepointFilters = new ArrayList<>();
		for ( DecisionModel<CONSUMED_EVENT_TYPE> p: decisionModels ) {
			EventQuery initQuery = p.initQuery();
			if ( initQuery != null && !initQuery.filter().isMatchNone() ) {
				savepointModels.add(p);
				savepointFilters.add(initQuery.filter());
			} else {
				plainModels.add(p);
			}
		}

		List<MergedRead<CONSUMED_EVENT_TYPE>> mergedReads = merge(plainModels);

		// The consistency boundary, pinned BEFORE any read: the domain stream's head. Every read below is
		// bounded at it and the append presents it as the expected reference, so however many physical
		// reads the command makes they share one boundary. Two reads happen sequentially and are not
		// atomic as a group -- an append matching one model's filter can land between them and, with
		// the lock reference taken from a later read, escape the check -- and pinning first is what
		// closes that window. It is also what the check costs: on PostgreSQL the probe walks every stream
		// event after the reference, and this stream is the whole bounded context, so a reference at a
		// quiet entity's own newest event walks everything the context appended since, where the head
		// leaves it the handful appended during this command.
		// head() reads the reference and nothing else -- no deserialization, no upcasting, no decryption
		// -- so whatever type sits at the head cannot fail the command or answer as an empty stream, and
		// the reference names the stored event whole: a boundary at it includes every event the stored
		// event upcasts into. An absent head is an empty stream and stays absent: an empty expected
		// reference under the lock filter is "I decided on an empty boundary", which the check defends.
		// Never substitute a read's own newest event for it -- that reference belongs to one read, not
		// to the group, and re-opens the window.
		EventReference boundary = decisionModels.isEmpty() ? null : queryEventStream.head().orElse(null);

		ProjectorMetrics accumulatedMetrics = ProjectorMetrics.empty();

		// project the plain models: one composite read per merged query, dispatching each event to the
		// plain models whose own eventQuery matches it
		for ( MergedRead<CONSUMED_EVENT_TYPE> read: mergedReads ) {
			List<DecisionModel<CONSUMED_EVENT_TYPE>> modelsForRead = read.models();
			CompositeDecisionModel<CONSUMED_EVENT_TYPE> composite = new CompositeDecisionModel<>(read.query(), modelsForRead);
			long start = System.currentTimeMillis();
			Projector<CONSUMED_EVENT_TYPE> projector = Projector.from(queryEventStream).into(composite).build();
			ProjectorMetrics metrics = ( boundary == null ) ? projector.run() : projector.runUntil(boundary);
			long durationMs = System.currentTimeMillis() - start;
			accumulatedMetrics = accumulatedMetrics.add(metrics);
			for ( int i = 0; i < modelsForRead.size(); i++ ) {
				decisionModelProjections.add(new DecisionModelProjection(
						modelsForRead.get(i).getClass(), durationMs, metrics.queriesDone(),
						metrics.eventsStreamed(), composite.handledBy(i), metrics.mostRecentEventReference()));
			}
		}

		// project each savepoint model through its own projector so its initQuery savepoint is honoured
		for ( DecisionModel<CONSUMED_EVENT_TYPE> p: savepointModels ) {
			long start = System.currentTimeMillis();
			Projector<CONSUMED_EVENT_TYPE> projector = Projector.from(queryEventStream).into(p).build();
			ProjectorMetrics metrics = ( boundary == null ) ? projector.run() : projector.runUntil(boundary);
			long durationMs = System.currentTimeMillis() - start;
			accumulatedMetrics = accumulatedMetrics.add(metrics);
			decisionModelProjections.add(new DecisionModelProjection(
					p.getClass(), durationMs, metrics.queriesDone(),
					metrics.eventsStreamed(), metrics.eventsHandled(), metrics.mostRecentEventReference()));
		}

		// the lock reference is the pinned boundary, absent for an empty stream or a command without
		// decision models (whose lock filter is matchNone, so the reference is moot)
		EventReference lastEventReference = boundary;

		projectorMetrics = accumulatedMetrics;

		// The optimistic-lock filter: the union of EVERY query the models were actually read with — each
		// model's eventQuery, and the initQuery of a savepoint model.
		// The eventQueries are collected here and not before the reads because a savepoint model only
		// learns its query once its initQuery has run — the Projector calls eventQuery() after handing it
		// those events — so a union taken up front would describe a query that was never executed. Where
		// such a model starts out matchNone, that union is matchNone, which is precisely
		// AppendCriteria.none(): the command would decide on facts it never locked and append with no
		// consistency check at all.
		// The initQueries are in it because a savepoint model decides on two reads and both are facts it
		// relied on: "the newest savepoint is X" is as much a decision as "these are the movements after
		// it". Locked on the eventQuery alone, an event of a savepoint type landing after the boundary
		// matches nothing in the criteria and the append is admitted — a command that stamped what the
		// savepoint told it (the active period, the carry-forward balance) then writes that stale answer
		// after the event that changed it, with nothing raised. The hole is systematic rather than
		// occasional, because the savepoint pattern asks for the two queries to name disjoint event types
		// (otherwise the savepoint is double-processed), so the types most able to invalidate the decision
		// are exactly the ones the eventQuery does not name. It is locked whether or not the model found a
		// savepoint: a model that found none replayed from the beginning, and a savepoint appearing after
		// the boundary makes that answer just as stale.
		// Direction and limit play no part — a filter carries neither — which is what lets a
		// backwards().limit(1) savepoint query join the union at all: the fact locked on is "no event of
		// these types, for these tags, after the boundary", not "the newest one is still the newest".
		// Every event matching this filter up to the boundary was read, by the same queries under the same
		// boundary, so "nothing matching appeared after the reference" is exactly the right check.
		List<EventFilter> readFilters = new ArrayList<>();
		for ( DecisionModel<CONSUMED_EVENT_TYPE> p: decisionModels ) {
			readFilters.add(p.eventQuery().filter());
		}
		readFilters.addAll(savepointFilters);
		EventFilter lockFilter = union(readFilters);

		commandResult = new CommandResultImpl<>(boundedContext, targetEventStream.id(), tracing, lockFilter, lastEventReference);
		return commandResult;
	}

	/**
	 * The union of the given filters, or match-none when there are none to combine.
	 * <p>
	 * Each member is stripped of its {@code until} first. A filter carrying one deems no event after it a
	 * new relevant fact, so as an {@code AppendCriteria} it admits every append and raises no
	 * {@code OptimisticLockingException} — the check off rather than narrowed, and silently, since the
	 * append reports success. A model is free to bound its own read that way; what this command decided on
	 * is bounded by the pinned head, which the criteria presents as its expected reference, and the filter
	 * says only which events are relevant. Stripping is also what makes the union well-formed:
	 * {@link EventFilter#or} refuses two members that do not share an {@code until}, which an unbounded
	 * initQuery united with a bounded eventQuery would otherwise hit.
	 */
	private static EventFilter union ( List<EventFilter> filters ) {
		EventFilter combined = null;
		for ( EventFilter filter: filters ) {
			EventFilter unbounded = filter.until(null);
			combined = ( combined == null ) ? unbounded : combined.or(unbounded);
		}
		return ( combined == null ) ? EventFilter.matchNone() : combined;
	}

	/**
	 * One physical read and the plain decision models projected from it.
	 */
	private record MergedRead<E> ( EventQuery query, List<DecisionModel<E>> models ) { }

	/**
	 * Reduces the plain models' queries to the minimal set of physical reads. Unlimited queries with
	 * the same direction and {@code until} are folded into one query through {@link EventQuery#or},
	 * which is the union of their filters (a match-all member makes the union match-all); a query
	 * carrying a limit is read on its own, since a limit over a union does not mean "the newest n of
	 * each". Direction and {@code until} are what {@code or} refuses to combine, so grouping on them
	 * is what keeps the fold well-defined. Reads come out in the order the models were given.
	 */
	private static <E> List<MergedRead<E>> merge ( List<DecisionModel<E>> plainModels ) {
		record GroupKey ( EventQuery.Direction direction, EventReference until ) { }
		List<MergedRead<E>> reads = new ArrayList<>();
		Map<GroupKey, Integer> groupIndex = new LinkedHashMap<>();
		for ( DecisionModel<E> model: plainModels ) {
			EventQuery query = model.eventQuery();
			if ( query.limit().isSet() ) {
				reads.add(new MergedRead<>(query, new ArrayList<>(List.of(model))));
				continue;
			}
			GroupKey key = new GroupKey(query.direction(), query.filter().until());
			Integer index = groupIndex.get(key);
			if ( index == null ) {
				groupIndex.put(key, reads.size());
				reads.add(new MergedRead<>(query, new ArrayList<>(List.of(model))));
			} else {
				MergedRead<E> read = reads.get(index);
				read.models().add(model);
				reads.set(index, new MergedRead<>(read.query().or(query), read.models()));
			}
		}
		return reads;
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
				if ( model.eventQuery().filter().matches(event) ) {
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
	public <READ_MODEL extends ReadModel<? extends CONSUMED_EVENT_TYPE>> READ_MODEL read ( Class<READ_MODEL> readModelClass, Object... constructorParams ) {
		return readModelModule.liveModel(readModelClass, tracing, constructorParams);
	}

}
