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

import java.lang.reflect.InvocationTargetException;

import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandContext;
import org.sliceworkz.eventmodeling.commands.CommandResult;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.module.boundedcontext.KernelEvent;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger;
import org.sliceworkz.eventmodeling.module.boundedcontext.PerformanceLogger.Metrics;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.stream.EventSource;

public class ProjectLiveModelUnboundedCommand <DOMAIN_EVENT_TYPE> implements Command<KernelEvent> {

	private Class<? extends ReadModel<? extends DOMAIN_EVENT_TYPE>> readModelClass;
	private Object[] constructorParams;
	private EventSource<DOMAIN_EVENT_TYPE> eventSource;
	private String boundedContext;
	private Instance instance;
	
	private ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel;
	
	public ProjectLiveModelUnboundedCommand ( String boundedContext, Instance instance, EventSource<DOMAIN_EVENT_TYPE> eventSource, Class<? extends ReadModel<? extends DOMAIN_EVENT_TYPE>> readModelClass, Object... constructorParams ) {
		this.boundedContext = boundedContext;
		this.instance = instance;
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
			readModel = (ReadModelWithMetaData<DOMAIN_EVENT_TYPE>) readModelClass.getDeclaredConstructors()[0].newInstance(constructorParams);
			Projector<DOMAIN_EVENT_TYPE> projector = Projector.from(eventSource).towards(readModel).build();
			ProjectorMetrics projectorMetrics = projector.run();
			long finish = System.currentTimeMillis();
			long duration = finish - start;
			Metrics metrics = new Metrics(duration, projectorMetrics.queriesDone(), projectorMetrics.eventsStreamed(), projectorMetrics.eventsHandled(), projectorMetrics.lastEventReference());
			PerformanceLogger.entry().context(boundedContext).instance(instance).metrics(metrics).type("readmodel.live").readmodel(readModel.readmodelName()).log();
			return result; 
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}
	
}