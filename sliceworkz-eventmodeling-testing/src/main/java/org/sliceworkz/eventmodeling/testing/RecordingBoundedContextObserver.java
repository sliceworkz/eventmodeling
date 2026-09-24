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
package org.sliceworkz.eventmodeling.testing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.observability.Observation;
import org.sliceworkz.eventmodeling.observability.Outcome;

/**
 * A {@link BoundedContextObserver} that keeps everything it is told, for a test to assert on.
 * <pre>{@code
 * RecordingBoundedContextObserver observer = new RecordingBoundedContextObserver();
 * Banking banking = BoundedContext.newBuilder(Banking.class)...observer(observer).build();
 *
 * banking.execute(new OpenAccountCommand(...));
 *
 * Recording execution = observer.last(Observation.CommandExecution.class);
 * assertInstanceOf(Outcome.Executed.class, execution.outcome().orElseThrow());
 * }</pre>
 * Every operation is a {@link Recording}: what was started, what it answered or failed with, whether it
 * was closed, and the recording it was started inside of, if any — so a test can check that a snapshot
 * load nests under the aggregate load it belongs to. Every failed listener delivery is a
 * {@link ListenerFailure}.
 * <p>
 * It also checks the contract the framework owes an observer, and keeps what it finds in
 * {@link #violations()}: a scope completed or failed twice, or both, or after it was closed, closed twice,
 * closed without an answer, or closed out of order. A test observing a context can assert that list is
 * empty and have checked the framework along the way.
 * <p>
 * Thread-safe; the nesting is tracked per thread, which is what the contract promises. The counterpart of
 * the event store's {@code RecordingObserver}, for the framework's own observations.
 */
public final class RecordingBoundedContextObserver implements BoundedContextObserver {

	private final List<Recording> recordings = new CopyOnWriteArrayList<>();
	private final List<ListenerFailure> listenerFailures = new CopyOnWriteArrayList<>();
	private final List<String> violations = new CopyOnWriteArrayList<>();
	private final ThreadLocal<Deque<Recording>> open = ThreadLocal.withInitial(ArrayDeque::new);

	@Override
	public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
		Deque<Recording> stack = open.get();
		Recording recording = new Recording(observation, stack.peek(), Thread.currentThread().getName());
		recordings.add(recording);
		stack.push(recording);
		return new RecordingScope<>(recording);
	}

	@Override
	public void listenerFailed ( String boundedContext, BoundedContextEvent event, Exception failure ) {
		listenerFailures.add(new ListenerFailure(boundedContext, event, failure));
	}

	/**
	 * Every operation observed so far, in the order they were started.
	 *
	 * @return a snapshot of the recordings
	 */
	public List<Recording> recordings ( ) {
		return List.copyOf(recordings);
	}

	/**
	 * The operations of one kind observed so far, in the order they were started.
	 *
	 * @param kind the kind of observation, e.g. {@code Observation.CommandExecution.class}
	 * @return a snapshot of the matching recordings
	 */
	public List<Recording> recordings ( Class<? extends Observation<?>> kind ) {
		return recordings.stream().filter(r -> kind.isInstance(r.observation())).toList();
	}

	/**
	 * The observations of one kind observed so far, in the order they were started.
	 *
	 * @param <T> the kind of observation
	 * @param kind the kind of observation
	 * @return a snapshot of the matching observations
	 */
	public <T extends Observation<?>> List<T> observations ( Class<T> kind ) {
		return recordings.stream().map(Recording::observation).filter(kind::isInstance).map(kind::cast).toList();
	}

	/**
	 * The most recent operation of one kind.
	 *
	 * @param kind the kind of observation
	 * @return the last matching recording
	 * @throws AssertionError if there is none
	 */
	public Recording last ( Class<? extends Observation<?>> kind ) {
		List<Recording> matching = recordings(kind);
		if ( matching.isEmpty() ) {
			throw new AssertionError("no %s was observed; observed: %s".formatted(kind.getSimpleName(), recordings));
		}
		return matching.getLast();
	}

	/**
	 * Every failed listener delivery reported so far, in order.
	 *
	 * @return a snapshot of the listener failures
	 */
	public List<ListenerFailure> listenerFailures ( ) {
		return List.copyOf(listenerFailures);
	}

	/**
	 * Every breach of the scope contract seen so far, described.
	 *
	 * @return a snapshot of the violations, empty when the framework kept the contract
	 */
	public List<String> violations ( ) {
		return List.copyOf(violations);
	}

	/**
	 * Forgets everything recorded so far, so a test can observe one step on its own.
	 */
	public void clear ( ) {
		recordings.clear();
		listenerFailures.clear();
		violations.clear();
	}

	/**
	 * A failed listener delivery.
	 *
	 * @param boundedContext the bounded context
	 * @param event the event the listener failed on
	 * @param failure what it threw
	 */
	public record ListenerFailure ( String boundedContext, BoundedContextEvent event, Exception failure ) { }

	/**
	 * One observed operation.
	 */
	public static final class Recording {

		private final Observation<?> observation;
		private final Recording parent;
		private final String thread;
		private volatile Outcome outcome;
		private volatile Throwable failure;
		private volatile boolean closed;

		private Recording ( Observation<?> observation, Recording parent, String thread ) {
			this.observation = observation;
			this.parent = parent;
			this.thread = thread;
		}

		/**
		 * @return what was started
		 */
		public Observation<?> observation ( ) {
			return observation;
		}

		/**
		 * @param <T> the expected kind
		 * @param kind the expected kind of observation
		 * @return what was started, as that kind
		 * @throws ClassCastException if it is another kind
		 */
		public <T extends Observation<?>> T observation ( Class<T> kind ) {
			return kind.cast(observation);
		}

		/**
		 * @return what the operation answered, empty if it did not (yet)
		 */
		public Optional<Outcome> outcome ( ) {
			return Optional.ofNullable(outcome);
		}

		/**
		 * @param <T> the expected kind
		 * @param kind the expected kind of outcome
		 * @return what the operation answered, as that kind
		 * @throws AssertionError if it answered nothing or something else
		 */
		public <T extends Outcome> T outcome ( Class<T> kind ) {
			if ( !kind.isInstance(outcome) ) {
				throw new AssertionError("expected %s to complete as %s, but it %s".formatted(
						observation, kind.getSimpleName(), outcome != null ? "completed as " + outcome : failure != null ? "failed with " + failure : "did not complete"));
			}
			return kind.cast(outcome);
		}

		/**
		 * @return what the operation failed with, empty if it did not fail
		 */
		public Optional<Throwable> failure ( ) {
			return Optional.ofNullable(failure);
		}

		/**
		 * @return the recording this one was started inside of, on the same thread
		 */
		public Optional<Recording> parent ( ) {
			return Optional.ofNullable(parent);
		}

		/**
		 * @return whether the scope was closed
		 */
		public boolean closed ( ) {
			return closed;
		}

		/**
		 * @return the name of the thread the operation was started on
		 */
		public String thread ( ) {
			return thread;
		}

		@Override
		public String toString ( ) {
			return "%s -> %s".formatted(observation, outcome != null ? outcome : failure != null ? "failed: " + failure : "(open)");
		}

	}

	private final class RecordingScope<O extends Outcome> implements Observation.Scope<O> {

		private final Recording recording;

		private RecordingScope ( Recording recording ) {
			this.recording = recording;
		}

		@Override
		public void completed ( O outcome ) {
			if ( checkReportable("completed") ) {
				recording.outcome = outcome;
			}
		}

		@Override
		public void failed ( Throwable failure ) {
			if ( checkReportable("failed") ) {
				recording.failure = failure;
			}
		}

		@Override
		public void close ( ) {
			if ( recording.closed ) {
				violations.add("%s closed twice".formatted(recording.observation));
				return;
			}
			if ( recording.outcome == null && recording.failure == null ) {
				violations.add("%s closed without completing or failing".formatted(recording.observation));
			}
			recording.closed = true;
			Deque<Recording> stack = open.get();
			if ( stack.peek() != recording ) {
				violations.add("%s closed out of order, or on another thread than it started on".formatted(recording.observation));
			}
			stack.remove(recording);
		}

		private boolean checkReportable ( String what ) {
			if ( recording.closed ) {
				violations.add("%s %s after it was closed".formatted(recording.observation, what));
				return false;
			}
			if ( recording.outcome != null || recording.failure != null ) {
				violations.add("%s %s after it had already %s".formatted(recording.observation, what, recording.outcome != null ? "completed" : "failed"));
				return false;
			}
			return true;
		}

	}

}
