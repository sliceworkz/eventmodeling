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

import java.util.Arrays;
import java.util.function.Consumer;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.AppendCriteria;

/**
 * The {@link LiveModelTest} of a live read model over the outbound stream: registered on
 * {@code builder.outbound()}, seeded with outbound events, read through {@code context.outbound().read(...)}.
 * <pre>{@code
 * given()
 *     .events(...)               // outbound events, appended to the outbound stream as fixture data
 * .when("some parameter")        // the read model's constructor parameters
 * .then()
 *     .liveModel(m -> ((MyReadModel) m).count()).is(2);
 * }</pre>
 * The events are appended as they are: nothing is dispatched, since the test registers
 * nothing but the read model.
 */
public abstract class OutboundLiveModelTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {

	public abstract Class<? extends ReadModel<OUTBOUND_EVENT_TYPE>> getLiveModelClass ( );

	@Override
	public void configure ( BoundedContextBuilder<?> boundedContextBuilder ) {
		boundedContextBuilder.outbound().readmodel(getLiveModelClass()).live();
	}

	public TestDefinition given ( ) {
		return new TestDefinition();
	}

	public class TestDefinition {

		private Object liveModel;

		@SafeVarargs
		public final TestDefinition events ( OUTBOUND_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e -> outboundStream().append(AppendCriteria.none(), Event.of(e, Tags.none())));
			return this;
		}

		public TestDefinition event ( OUTBOUND_EVENT_TYPE event, Tags tags ) {
			outboundStream().append(AppendCriteria.none(), Event.of(event, tags));
			return this;
		}

		public TestDefinition when ( Object... args ) {
			this.liveModel = kernel().outbound().read(getLiveModelClass(), args);
			return this;
		}

		public TestResult then ( ) {
			if ( this.liveModel == null ) {
				this.liveModel = kernel().outbound().read(getLiveModelClass());
			}
			return new TestResult(this.liveModel);
		}
	}

	public class TestResult {

		private final Object actualModel;
		private Object mappedActualModel;

		TestResult ( Object actualModel ) {
			this.actualModel = actualModel;
		}

		public void liveModelIs ( Object expectedModel ) {
			assertCompareJsonString(expectedModel, actualModel, "live model");
		}

		public TestResult liveModel ( LiveModelTest.DataMapper dataMapper ) {
			mappedActualModel = dataMapper.map(actualModel);
			return this;
		}

		public TestResult is ( Object expectedModel ) {
			assertCompareObjects(expectedModel, mappedActualModel, "live model");
			return this;
		}

		public void satisfies ( Consumer<Object> assertion ) {
			assertion.accept(mappedActualModel);
		}
	}

}
