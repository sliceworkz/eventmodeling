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
package org.sliceworkz.eventmodeling.module.readmodels;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.module.boundedcontext.KernelEvent;
import org.sliceworkz.eventmodeling.module.boundedcontext.KernelEvent.Metrics;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.stream.EventSource;

class ProjectLiveModelCommand<DOMAIN_EVENT_TYPE> implements Command<KernelEvent> {

	private Logger LOGGER = LoggerFactory.getLogger(ProjectLiveModelCommand.class);
	
	private Class<? extends ReadModel<? extends DOMAIN_EVENT_TYPE>> readModelClass;
	private Object[] constructorParams;
	private EventSource<DOMAIN_EVENT_TYPE> eventSource;
	
	private ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel;
	
	public ProjectLiveModelCommand ( EventSource<DOMAIN_EVENT_TYPE> eventSource, Class<? extends ReadModel<? extends DOMAIN_EVENT_TYPE>> readModelClass, Object... constructorParams ) {
		this.eventSource = eventSource;
		this.readModelClass = readModelClass;
		this.constructorParams = constructorParams;
	}
	
	public ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel ( ) {
		return readModel;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public CommandResult<KernelEvent,KernelEvent> execute(CommandContext<KernelEvent, KernelEvent> context) {
		long start = System.currentTimeMillis();
		CommandResult<KernelEvent,KernelEvent> result = context.noDecisionModels();
		try {
			readModel = (ReadModelWithMetaData) selectConstructor(readModelClass, constructorParams).newInstance(constructorParams);
			Projector<DOMAIN_EVENT_TYPE> projector = Projector.from(eventSource).towards(readModel).build();
			ProjectorMetrics projectorMetrics = projector.run();
			long finish = System.currentTimeMillis();
			long duration = finish - start;
			return result.raiseEvent(new KernelEvent.LiveModelProjected(readModelClass, map(duration, projectorMetrics), projector.eventQuery()), Tags.none()); 
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
			LOGGER.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}
	
	Constructor<?> selectConstructor ( Class<?> readModelClass, Object[] constructorParams ) {
		for ( Constructor<?> ctr : readModelClass.getDeclaredConstructors() ) {
			// TODO improve constructor selection, not only on parameter count but also on type!
			if ( ctr.getParameterCount() == constructorParams.length ) {
				return ctr;
			}
		}
		throw new IllegalArgumentException("no public constructur found on " + readModelClass + " for parameters " + constructorParams);
	}
	
	private Metrics map ( long duration, ProjectorMetrics projectorMetrics ) {
		return new Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
	}
	
}