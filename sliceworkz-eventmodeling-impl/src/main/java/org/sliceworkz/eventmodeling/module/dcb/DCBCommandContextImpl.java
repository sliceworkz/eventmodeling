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
import java.util.Optional;

import org.sliceworkz.eventmodeling.module.timing.Elapsed;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.commands.DecisionModel;
import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.rules.BusinessRule;
import org.sliceworkz.eventmodeling.rules.Overrides;
import org.sliceworkz.eventmodeling.rules.RuleCheck;
import org.sliceworkz.eventmodeling.rules.RuleFollowUp;
import org.sliceworkz.eventmodeling.rules.RuleViolation;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
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

	private final RuleBook ruleBook;

	private final List<RuleFollowUp> followUps = new ArrayList<>();

	/**
	 * Per-decision-model projection result emitted as a {@code DecisionModelProjected} bounded-context
	 * event. {@code eventsStreamed}/{@code queriesDone}/{@code durationMicros}/{@code until} describe the
	 * physical read the model was projected from (shared by all models that were read together via a
	 * single merged query); {@code eventsHandled} is the number of those events relevant to (handled by)
	 * this particular decision model.
	 */
	public record DecisionModelProjection ( Class<?> decisionModelClass, long durationMicros, long queriesDone, long eventsStreamed, long eventsHandled, EventReference until ) { }
	
	public DCBCommandContextImpl ( String boundedContext, ReadModelModule<CONSUMED_EVENT_TYPE> readModelModule, EventStream<CONSUMED_EVENT_TYPE> queryEventStream, EventStream<PRODUCED_EVENT_TYPE> targetEventStream, Tracing tracing ) {
		this(boundedContext, readModelModule, queryEventStream, targetEventStream, tracing, Overrides.none());
	}

	public DCBCommandContextImpl ( String boundedContext, ReadModelModule<CONSUMED_EVENT_TYPE> readModelModule, EventStream<CONSUMED_EVENT_TYPE> queryEventStream, EventStream<PRODUCED_EVENT_TYPE> targetEventStream, Tracing tracing, Overrides overrides ) {
		this.ruleBook = new RuleBook(overrides);
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

		// First the savepoints. A model with an initQuery decides on the newest savepoint and on what came
		// after it: its savepoint is read on its own (a backwards, limited read cannot be united with
		// anything -- the newest of either is not the newest of each), handed to the model, and its
		// eventQuery -- asked only now, since a model may learn it from its savepoint -- becomes a filter
		// bounded after that savepoint. A forward, unlimited one then joins the merged read below like a
		// plain model's: one physical read for every model, where each savepoint model used to page its
		// own. The init filters are kept for the lock filter.
		List<ModelRead<CONSUMED_EVENT_TYPE>> modelReads = new ArrayList<>();
		List<EventFilter> savepointFilters = new ArrayList<>();
		for ( DecisionModel<CONSUMED_EVENT_TYPE> model: decisionModels ) {
			EventQuery initQuery = model.initQuery();
			if ( initQuery == null || initQuery.filter().isMatchNone() ) {
				modelReads.add(new ModelRead<>(model, model.eventQuery(), null, null));
				continue;
			}
			savepointFilters.add(initQuery.filter());
			SavepointRead<CONSUMED_EVENT_TYPE> savepoint = new SavepointRead<>(model, initQuery);
			long start = Elapsed.start();
			ProjectorMetrics metrics = run(Projector.from(queryEventStream).into(savepoint).build(), boundary);
			accumulatedMetrics = accumulatedMetrics.add(metrics);
			DecisionModelProjection initProjection = new DecisionModelProjection(model.getClass(), Elapsed.microsSince(start),
					metrics.queriesDone(), metrics.eventsStreamed(), metrics.eventsHandled(), metrics.mostRecentEventReference());
			EventQuery eventQuery = model.eventQuery();
			if ( eventQuery.direction() == EventQuery.Direction.FORWARD && !eventQuery.limit().isSet() ) {
				EventQuery afterSavepoint = new EventQuery(eventQuery.filter().afterIfLater(savepoint.last), EventQuery.Direction.FORWARD, null, eventQuery.notAfter());
				modelReads.add(new ModelRead<>(model, afterSavepoint, initProjection, null));
			} else {
				// a backward or limited read after a savepoint is read on its own, from the savepoint on
				modelReads.add(new ModelRead<>(model, eventQuery, initProjection, savepoint.last));
			}
		}

		// Then every model's own read, reduced to the minimal set of physical reads and each bounded at the
		// pinned head; a model is handed the events that match its own filter within its own bounds
		for ( MergedRead<CONSUMED_EVENT_TYPE> read: merge(modelReads) ) {
			CompositeDecisionModel<CONSUMED_EVENT_TYPE> composite = new CompositeDecisionModel<>(read.query(), read.models());
			long start = Elapsed.start();
			Projector.Builder<CONSUMED_EVENT_TYPE> builder = Projector.from(queryEventStream).into(composite);
			if ( read.startingAfter() != null ) {
				builder = builder.startingAfter(read.startingAfter());
			}
			ProjectorMetrics metrics = run(builder.build(), boundary);
			long durationMicros = Elapsed.microsSince(start);
			accumulatedMetrics = accumulatedMetrics.add(metrics);
			for ( int i = 0; i < read.models().size(); i++ ) {
				ModelRead<CONSUMED_EVENT_TYPE> modelRead = read.models().get(i);
				DecisionModelProjection init = modelRead.initProjection();
				decisionModelProjections.add(init == null
						? new DecisionModelProjection(modelRead.model().getClass(), durationMicros, metrics.queriesDone(),
								metrics.eventsStreamed(), composite.handledBy(i), metrics.mostRecentEventReference())
						: new DecisionModelProjection(modelRead.model().getClass(), init.durationMicros() + durationMicros,
								init.queriesDone() + metrics.queriesDone(), init.eventsStreamed() + metrics.eventsStreamed(),
								init.eventsHandled() + composite.handledBy(i), metrics.mostRecentEventReference() != null ? metrics.mostRecentEventReference() : init.until()));
			}
		}

		// the lock reference is the pinned boundary, absent for an empty stream or a command without
		// decision models (whose lock filter is matchNone, so the reference is moot)
		EventReference lastEventReference = boundary;

		projectorMetrics = accumulatedMetrics;

		// The optimistic-lock filter: the union of EVERY filter the models were read with — each model's
		// read, and the initQuery of a savepoint model.
		// A savepoint model's read is taken as it was made, after its savepoint: the criteria drops that
		// lower bound (it lies at or before the head the criteria compares against), so what is locked is
		// "no event of these types, for these tags, after the boundary". A model's own read bound
		// (notAfter) is a property of its read and not of its filter, so it never reaches the criteria;
		// the bounds of its items do, and the criteria leaves out an item bounded at or before the head --
		// nothing appended later can match it.
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
		// backwards().limit(1) savepoint query join the union at all.
		List<EventFilter> readFilters = new ArrayList<>();
		for ( ModelRead<CONSUMED_EVENT_TYPE> modelRead: modelReads ) {
			readFilters.add(modelRead.query().filter());
		}
		readFilters.addAll(savepointFilters);
		EventFilter lockFilter = union(readFilters);

		commandResult = new CommandResultImpl<>(boundedContext, targetEventStream.id(), tracing, lockFilter, lastEventReference);
		return commandResult;
	}

	private static <E> ProjectorMetrics run ( Projector<E> projector, EventReference boundary ) {
		return ( boundary == null ) ? projector.run() : projector.runUntil(boundary);
	}

	/**
	 * The union of the given filters, or match-none when there are none to combine. Every member keeps
	 * the bounds of its items, and {@link EventFilter#or} unites differently bounded items as they are.
	 */
	private static EventFilter union ( List<EventFilter> filters ) {
		EventFilter combined = null;
		for ( EventFilter filter: filters ) {
			combined = ( combined == null ) ? filter : combined.or(filter);
		}
		return ( combined == null ) ? EventFilter.matchNone() : combined;
	}

	/**
	 * One decision model and the read it is projected from: its eventQuery, bounded after its savepoint
	 * when it has one. {@code initProjection} is what reading the savepoint cost, null for a plain model;
	 * {@code startingAfter} is set only for a savepoint model whose read could not be bounded by its filter
	 * (backward or limited), and is then read on its own from there.
	 */
	private record ModelRead<E> ( DecisionModel<E> model, EventQuery query, DecisionModelProjection initProjection, EventReference startingAfter ) { }

	/**
	 * One physical read and the decision models projected from it.
	 */
	private record MergedRead<E> ( EventQuery query, List<ModelRead<E>> models, EventReference startingAfter ) { }

	/**
	 * Reduces the models' reads to the minimal set of physical reads. Unlimited reads with the same
	 * direction and read bound are folded into one, over the union of their filters (a match-all member
	 * makes the union match-all), each item keeping its own bounds -- so the savepoint models' reads, each
	 * bounded after its own savepoint, are one read however many there are.
	 * <p>
	 * A read whose items carry bounds is folded only with others that do. On PostgreSQL a union of
	 * differently bounded items is a statement of its own, one ordered branch per item, planned on every
	 * execution; folding an unbounded read into it costs that read the single statement and the cached plan
	 * it has on its own, on every page. Measured on a command deciding on three savepoint models beside one
	 * plain model that pages through ~1.400 events (PG18): one fold of everything read in 30,1 ms against
	 * 26,6 before any merging, where keeping the two kinds apart reads in 25,7 -- the same two reads saved
	 * either way.
	 * <p>
	 * A read carrying a limit is read on its own, since a limit over a union does not mean "the newest n
	 * of each", and so is a read that has to start after a reference of its own. Reads come out in the
	 * order the models were given.
	 */
	private static <E> List<MergedRead<E>> merge ( List<ModelRead<E>> modelReads ) {
		record GroupKey ( EventQuery.Direction direction, EventReference notAfter, boolean bounded ) { }
		List<MergedRead<E>> reads = new ArrayList<>();
		Map<GroupKey, Integer> groupIndex = new LinkedHashMap<>();
		for ( ModelRead<E> modelRead: modelReads ) {
			EventQuery query = modelRead.query();
			if ( query.limit().isSet() || modelRead.startingAfter() != null ) {
				reads.add(new MergedRead<>(query, new ArrayList<>(List.of(modelRead)), modelRead.startingAfter()));
				continue;
			}
			GroupKey key = new GroupKey(query.direction(), query.notAfter(), query.filter().isBounded());
			Integer index = groupIndex.get(key);
			if ( index == null ) {
				groupIndex.put(key, reads.size());
				reads.add(new MergedRead<>(query, new ArrayList<>(List.of(modelRead)), null));
			} else {
				MergedRead<E> read = reads.get(index);
				read.models().add(modelRead);
				EventQuery united = new EventQuery(read.query().filter().or(query.filter()), query.direction(), null, query.notAfter());
				reads.set(index, new MergedRead<>(united, read.models(), null));
			}
		}
		return reads;
	}

	/**
	 * A savepoint model's initQuery as a projection of its own: the savepoint read, handed to the model,
	 * remembering the reference of the last event handed over -- where the model's forward read resumes,
	 * exactly as a {@code Projector} running the initQuery itself resumes its eventQuery.
	 */
	private static final class SavepointRead<E> implements org.sliceworkz.eventstore.projection.Projection<E> {

		private final DecisionModel<E> model;
		private final EventQuery initQuery;
		private EventReference last;

		SavepointRead ( DecisionModel<E> model, EventQuery initQuery ) {
			this.model = model;
			this.initQuery = initQuery;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return initQuery;
		}

		@Override
		public void when ( Event<E> event ) {
			model.when(event);
			last = event.reference();
		}
	}

	/**
	 * A {@link Projection} over a merged read that dispatches each streamed event to the decision models
	 * whose own read it belongs to -- their filter, items bounds included, and their own read bound --
	 * recording per model how many events each handled.
	 */
	private static final class CompositeDecisionModel<E> implements org.sliceworkz.eventstore.projection.Projection<E> {

		private final EventQuery mergedQuery;
		private final List<ModelRead<E>> models;
		private final long[] handled;

		CompositeDecisionModel ( EventQuery mergedQuery, List<ModelRead<E>> models ) {
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
				EventQuery own = models.get(i).query();
				EventReference notAfter = own.notAfter();
				if ( ( notAfter == null || !event.reference().storedEventHappenedAfter(notAfter) ) && own.filter().matches(event) ) {
					models.get(i).model().when(event);
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
	 * A command that selected neither {@code decisionModels(...)} nor {@code noDecisionModels()} is
	 * taken to have decided on nothing, exactly as if it had called {@code noDecisionModels()}. That is
	 * the only thing its silence can mean: the {@code CommandResult} events are raised on is handed out
	 * by those two calls alone, so a command that made neither raised nothing, and there is nothing to
	 * guard. The result is match-none with no reference, no head is taken and nothing is read, so such
	 * an execution appends nothing and reports {@code CommandExecuted} with no events — the natural
	 * outcome of a command that reads, finds nothing to do and returns, or that rejects before deciding.
	 * The alternative — rejecting the silence as a mistake — loses because it fails an execution whose
	 * outcome is already fully determined, for a call that would change nothing about it.
	 */
	public CommandResultImpl<CONSUMED_EVENT_TYPE, PRODUCED_EVENT_TYPE> getCommandResult ( ) {
		if ( commandResult == null ) {
			noDecisionModels();
		}
		return commandResult;
	}

	public Tracing tracing ( ) {
		return tracing;
	}

	@Override
	public RuleCheck check ( BusinessRule rule, boolean violated, String message ) {
		return ruleBook.check(rule, violated, message);
	}

	@Override
	public List<RuleViolation> ruleViolations ( ) {
		return ruleBook.ruleViolations();
	}

	@Override
	public RuleFollowUp justifies ( BusinessRule rule, EventId event, String justification ) {
		return followUp(rule, event, RuleFollowUp.Kind.JUSTIFIED, justification, "justifies");
	}

	@Override
	public RuleFollowUp enforces ( BusinessRule rule, EventId event, String note ) {
		return followUp(rule, event, RuleFollowUp.Kind.ENFORCED, note, "enforces");
	}

	private RuleFollowUp followUp ( BusinessRule rule, EventId event, RuleFollowUp.Kind kind, String note, String method ) {
		if ( rule == null || event == null ) {
			throw new IllegalArgumentException(method + "(...) needs the business rule and the id of the event it follows up");
		}
		RuleFollowUp followUp = new RuleFollowUp(rule.id(), event.value(), kind, ( note == null || note.isBlank() ) ? null : note.strip());
		followUps.add(followUp);
		return followUp;
	}

	/**
	 * @return the follow-ups this execution made, in the order it made them
	 */
	List<RuleFollowUp> followUps ( ) {
		return List.copyOf(followUps);
	}

	@Override
	public Optional<String> actor ( ) {
		String actor = tracing.actor();
		return ( actor == null || actor.isBlank() ) ? Optional.empty() : Optional.of(actor.strip());
	}

	/**
	 * @return the business rules this execution checked, judged by the kernel after the command ran
	 */
	RuleBook ruleBook ( ) {
		return ruleBook;
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
