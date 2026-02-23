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

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.events.Tags;

public abstract class LiveModelTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> extends AbstractBoundedContextTest<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> {
	
	public abstract Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> getLiveModelClass ( );
	
	@Override
	public void configure ( BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> boundedContextBuilder ) {
		boundedContextBuilder.readmodel(getLiveModelClass()).live();
	}
	
	public TestDefinition given ( ) {
		return new TestDefinition();
	}

	@FunctionalInterface
	public interface DataMapper {
		Object map ( Object from );
	}
	
	public interface TestResult<DOMAIN_EVENT_TYPE> {
		
		// TODO improve, this is not clear.  or "liveModelIs" or ".liveModel(()->..).is(...) => "map()" or something?
		
		void liveModelIs ( Object expectedModel );
		TestResult<DOMAIN_EVENT_TYPE> liveModel ( DataMapper dataMapper );
		TestResult<DOMAIN_EVENT_TYPE> is ( Object expectedModel );
	}
	
	public class TestDefinition {
	
		TestResult<DOMAIN_EVENT_TYPE> result;
		Object liveModel;
		
		public TestDefinition events ( @SuppressWarnings("unchecked") DOMAIN_EVENT_TYPE... events ) {
			Arrays.asList(events).forEach(e->kernel().event(e));
			return this;
		}
		
		public TestDefinition event ( DOMAIN_EVENT_TYPE event, Tags tags ) {
			kernel().event(event, tags);
			return this;
		}
		
		public TestDefinition when ( Object... args ) {
			this.liveModel = kernel().read(getLiveModelClass(), args);
			return this;
		}
			
		public TestResult<DOMAIN_EVENT_TYPE> then ( ) {
			if ( this.liveModel == null ) {
				this.liveModel = kernel().read(getLiveModelClass());
			}
			return new TestResultImpl(this.liveModel);
		}
		
	}
	
	public class TestResultImpl implements TestResult<DOMAIN_EVENT_TYPE> {
		
		private Object actualModel;
		private Object mappedActualModel;
		
		public TestResultImpl ( Object actualModel ) {
			this.actualModel = actualModel;
		}
		
		@Override
		public void liveModelIs ( Object expectedModel ) {
			assertCompareJsonString(expectedModel, actualModel, "live model");
		}
		
		@Override
		public TestResult<DOMAIN_EVENT_TYPE> liveModel ( DataMapper dataMapper ) {
			mappedActualModel = dataMapper.map(actualModel);
			return this;
		}

		@Override
		public TestResult<DOMAIN_EVENT_TYPE> is(Object expectedModel) {
			assertCompareObjects(expectedModel, mappedActualModel, "live model");
			return this;
		}

	}

}
