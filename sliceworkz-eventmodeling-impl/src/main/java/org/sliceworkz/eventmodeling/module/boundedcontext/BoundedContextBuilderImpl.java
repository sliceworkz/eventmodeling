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
package org.sliceworkz.eventmodeling.module.boundedcontext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.Banner;
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateSpecification;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.FeaturesSpecification;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.module.aggregates.AggregateModule;
import org.sliceworkz.eventmodeling.module.aggregates.AggregateSpecificationImpl;
import org.sliceworkz.eventmodeling.module.automation.AutomationModule;
import org.sliceworkz.eventmodeling.module.dcb.DCBModule;
import org.sliceworkz.eventmodeling.module.inbound.InboundModule;
import org.sliceworkz.eventmodeling.module.outbound.OutboundModule;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.LiveModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.LongLivedReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.slices.AnnotationBasedDiscoveryAndConfiguration;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSliceConfiguration;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

public class BoundedContextBuilderImpl<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> implements BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {

	static {
		Banner.printBanner();
	}
	
	private static final Logger LOGGER = LoggerFactory.getLogger(BoundedContextBuilderImpl.class);
	
	private String name;
	
	private List<LiveModelSpecificationImpl> liveModelSpecs = new ArrayList<>();
	private List<LongLivedReadModelSpecificationImpl> longLivedReadModelSpecs = new ArrayList<>();
	private List<Translator<? extends INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> translatorSpecs = new ArrayList<>();
	private List<Dispatcher<? extends OUTBOUND_EVENT_TYPE>> dispatcherSpecs = new ArrayList<>();
	private List<Automation<?,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> automations = new ArrayList<>();
	private List<AggregateSpecificationImpl<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> aggregateSpecifications = new ArrayList<>();
	
	private Class<DOMAIN_EVENT_TYPE> domainEventRootType;
	private Class<INBOUND_EVENT_TYPE> inboundEventRootType;
	private Class<OUTBOUND_EVENT_TYPE> outboundEventRootType;
	
	private FeaturesSpecificationImpl<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> featuresSpecification = new FeaturesSpecificationImpl<>(this);
	
	private MeterRegistry meterRegistry = Metrics.globalRegistry;
	
	private EventStorage eventStorage;
	
	private Instance instance;
	
	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> name ( String name ) {
		this.name = name;
		return this;
	}
	
	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> eventTypes ( 
			Class<DOMAIN_EVENT_TYPE> domainEventRootType,
			Class<INBOUND_EVENT_TYPE> inboundEventRootType,
			Class<OUTBOUND_EVENT_TYPE> outboundEventRootType
			) {
		this.domainEventRootType = domainEventRootType;
		this.inboundEventRootType = inboundEventRootType;
		this.outboundEventRootType = outboundEventRootType;
		 return this;
	}

	@Override
	public FeaturesSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> features ( ) {
		return featuresSpecification;
	}
	
	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> instance ( Instance instance ) {
		this.instance = instance;
		return this;
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> meterRegistry ( MeterRegistry meterRegistry ) {
		if ( meterRegistry != null ) {
			this.meterRegistry = meterRegistry;
		} else {
			this.meterRegistry = Metrics.globalRegistry;
		}
		return this;
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> eventStorage ( EventStorage eventStorage ) {
		this.eventStorage = eventStorage;
		return this;
	}

	@Override
	public LiveModelSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> readmodel ( Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass ) {
		var m = new LiveModelSpecificationImpl(this, readModelClass);
		liveModelSpecs.add(m);
		return m;
	}

	@Override
	public LongLivedReadModelSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> readmodel ( ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel ) {
		var m = new LongLivedReadModelSpecificationImpl(this, readModel);
		longLivedReadModelSpecs.add(m);
		return m;
	}

	@Override
	public <TODO_ITEM_TYPE> BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>  automation ( Automation<TODO_ITEM_TYPE,DOMAIN_EVENT_TYPE,OUTBOUND_EVENT_TYPE> automation ) {
		automations.add(automation);
		return this;
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>  translator ( Translator<? extends INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE> translator ) {
		translatorSpecs.add(translator);
		return this;
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>  translator ( Class<? extends Translator<? extends INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> translatorClass ) {
		try {
			return translator(translatorClass.getDeclaredConstructor(new Class[0]).newInstance());
		} catch (InvocationTargetException e) {
			throw new RuntimeException(e);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		} catch (InstantiationException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>  dispatcher ( Dispatcher<? extends OUTBOUND_EVENT_TYPE> dispatcher ) {
		dispatcherSpecs.add(dispatcher);
		return this;
	}

	@Override
	public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>  dispatcher ( Class<? extends Dispatcher<? extends OUTBOUND_EVENT_TYPE>> dispatcherClass ) {
		try {
			return dispatcher(dispatcherClass.getDeclaredConstructor(new Class[0]).newInstance());
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		} catch (InstantiationException e) {
			throw new RuntimeException(e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public AggregateSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> aggregate(
			Class<? extends Aggregate<DOMAIN_EVENT_TYPE>> aggregateClass) {
		if ( aggregateClass == null ) {
			throw new IllegalArgumentException();
		}
		var aggregateSpecification = new AggregateSpecificationImpl<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> (this, aggregateClass);
		this.aggregateSpecifications.add(aggregateSpecification);
		
		return aggregateSpecification;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> build ( ) {
		return build(BoundedContext.class);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends BoundedContext<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>> T build ( Class<T> returnType ) {

		if ( instance == null ) {
			throw new IllegalArgumentException("instance not set");
		}
		if (  name == null ) {
			throw new IllegalArgumentException("name not set");
		}
		
		logEventTypes("DOMAIN", domainEventRootType);
		logEventTypes("INBOUND", inboundEventRootType);
		logEventTypes("OUTBOUND", outboundEventRootType);
		
		EventStream<Object> readAllInStoreEventStream;
		EventStream<DOMAIN_EVENT_TYPE> domainEventStream;
		EventStream<INBOUND_EVENT_TYPE> inboundEventStream;
		EventStream<OUTBOUND_EVENT_TYPE> outboundEventStream;
		EventStream<KernelEvent> observabilityEventStream; 
		
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage, meterRegistry);
		domainEventStream = eventStore.getEventStream(EventStreamId.forContext(name).withPurpose("domain"), domainEventRootType);
		inboundEventStream = eventStore.getEventStream(EventStreamId.forContext(name).withPurpose("inbound"), inboundEventRootType);
		outboundEventStream = eventStore.getEventStream(EventStreamId.forContext(name).withPurpose("outbound"), outboundEventRootType);
		observabilityEventStream = eventStore.getEventStream(EventStreamId.forContext(name).withPurpose("observability"), KernelEvent.class);
		readAllInStoreEventStream = eventStore.getEventStream(EventStreamId.anyContext().anyPurpose());

		List<FeatureSliceConfiguration<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> deployedFeatureSlices = Collections.emptyList();
		List<FeatureSliceConfiguration<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE>> undeployedFeatureSlices = Collections.emptyList();
		
		if ( featuresSpecification.rootPackage() != null ) {
			deployedFeatureSlices = 
			AnnotationBasedDiscoveryAndConfiguration.<FeatureSliceConfiguration<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE>>instantiateAndConfigure(
					FeatureSlice.class,
					featuresSpecification.rootPackage(),
					featuresSpecification.filter(),
					slice -> {
							if ( featuresSpecification.preConfigure() != null ) {
								featuresSpecification.preConfigure().accept(slice);
							}
							if ( featuresSpecification.mustDeployCommands() ) {
								slice.configureCommand(this);
							}
							if ( featuresSpecification.mustDeployQueries() ) {
								slice.configureQuery(this);
							}
							if ( featuresSpecification.mustDeployAutomations() ) {
								slice.configureAutomation(this);
							}
						});
			
			undeployedFeatureSlices = 
			AnnotationBasedDiscoveryAndConfiguration.<FeatureSliceConfiguration<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE>>instantiateAndConfigure(
					FeatureSlice.class,
					featuresSpecification.rootPackage(),
					featuresSpecification.filter().negate(),
					fs->{});
		} else {
			LOGGER.warn("no features rootPackage");
		}
		
		Collection<Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>>> liveModelClasses = liveModelSpecs.stream().map(LiveModelSpecificationImpl::readModelClass).collect(Collectors.toCollection(ArrayList::new));
		
		Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> consistentReadModels = longLivedReadModelSpecs.stream().filter(s->s.consistency()==Consistency.CONSISTENT).map(LongLivedReadModelSpecificationImpl::readModel).collect(Collectors.toCollection(ArrayList::new));
		Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentSharedReadModels = longLivedReadModelSpecs.stream().filter(s->s.consistency()==Consistency.EVENTUALLY_CONSISTENT&&s.isShared()).map(LongLivedReadModelSpecificationImpl::readModel).collect(Collectors.toCollection(ArrayList::new));
		Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentLocalReadModels = longLivedReadModelSpecs.stream().filter(s->s.consistency()==Consistency.EVENTUALLY_CONSISTENT&&s.isLocal()&&!s.isEphemeral()).map(LongLivedReadModelSpecificationImpl::readModel).collect(Collectors.toCollection(ArrayList::new));
		Collection<ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> eventuallyConsistentEphemeralReadModels = longLivedReadModelSpecs.stream().filter(s->s.consistency()==Consistency.EVENTUALLY_CONSISTENT&&s.isLocal()&&s.isEphemeral()).map(LongLivedReadModelSpecificationImpl::readModel).collect(Collectors.toCollection(ArrayList::new));
		
		Collection<Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>> translators = translatorSpecs.stream().map(i->(Translator<INBOUND_EVENT_TYPE,DOMAIN_EVENT_TYPE>)i).collect(Collectors.toCollection(ArrayList::new));
		InboundModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> im = new InboundModule<>(name, inboundEventStream, translators, instance);

		Collection<Dispatcher<OUTBOUND_EVENT_TYPE>> dispatchers = dispatcherSpecs.stream().map(i->(Dispatcher<OUTBOUND_EVENT_TYPE>)i).collect(Collectors.toCollection(ArrayList::new));
		OutboundModule<OUTBOUND_EVENT_TYPE> om = new OutboundModule<OUTBOUND_EVENT_TYPE>(name, outboundEventStream, dispatchers, instance);
		
		AutomationModule<DOMAIN_EVENT_TYPE,INBOUND_EVENT_TYPE,OUTBOUND_EVENT_TYPE> am = new AutomationModule<>(name, domainEventStream, automations, instance);
		
		ReadModelModule<DOMAIN_EVENT_TYPE> rmm = new ReadModelModule<DOMAIN_EVENT_TYPE>(name, domainEventStream, readAllInStoreEventStream, liveModelClasses, consistentReadModels, eventuallyConsistentSharedReadModels, eventuallyConsistentLocalReadModels, eventuallyConsistentEphemeralReadModels, instance, meterRegistry);
		DCBModule<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE> dcb = new DCBModule<DOMAIN_EVENT_TYPE, OUTBOUND_EVENT_TYPE>(name, instance, rmm, domainEventStream, outboundEventStream, false, meterRegistry);
		
		AggregateModule<DOMAIN_EVENT_TYPE> aggregateModule = new AggregateModule<DOMAIN_EVENT_TYPE>(name, instance, aggregateSpecifications, domainEventStream, meterRegistry);

		BoundedContextImpl<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> bc = 
				new BoundedContextImpl<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE>(name, deployedFeatureSlices, undeployedFeatureSlices, domainEventStream, inboundEventStream, outboundEventStream, observabilityEventStream, dcb, aggregateModule, rmm, am, im, om, instance, meterRegistry);
		
		// this is only possible after creation
		am.setCapabilitiesDelegate(bc);
		im.setCapabilitiesDelegate(bc);
				
		if ( returnType.isInterface()) {
			return proxy(bc, returnType);
		} else {
			return (T)bc; 
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> T proxy ( BoundedContextImpl<?,?,?> boundedContext, Class<?> interfaceClass ) {
		return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[] {interfaceClass}, new InvocationHandler() {
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				return method.invoke(boundedContext, args);
			}
		});
	}

	public class LiveModelSpecificationImpl implements LiveModelSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {
		
		private BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> builder;
		private Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass;
		private Consistency consistency = Consistency.LIVE;
		
		public LiveModelSpecificationImpl ( BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> builder, Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass ) {
			this.builder = builder;
			this.readModelClass = readModelClass;
		}

		@Override
		public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> live ( ) {
			this.consistency = Consistency.LIVE;
			return builder;
		}

		@Override
		public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> consistent ( ) {
			throw new IllegalArgumentException("LIVE read model - cannot be updated CONSISTENTly");
		}

		@Override
		public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> eventuallyConsistent ( ) {
			throw new IllegalArgumentException("LIVE read model - cannot be updated EVENTUALLY CONSISTENT");
		}

		public Consistency consistency ( ) {
			return consistency;
		}
		
		public Class<? extends ReadModelWithMetaData<DOMAIN_EVENT_TYPE>> readModelClass ( ) {
			return readModelClass;
		}

	}

	public class LongLivedReadModelSpecificationImpl implements LongLivedReadModelSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> {
		
		private boolean shared = true;
		private boolean ephemeral = false;
		private BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> builder;
		private ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel;
		private Consistency consistency = Consistency.EVENTUALLY_CONSISTENT;

		public LongLivedReadModelSpecificationImpl ( BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> builder, ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel ) {
			this.builder = builder;
			this.readModel= readModel;
		}
		
		@Override
		public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> live ( ) {
			throw new IllegalArgumentException("state-based readmodel - cannot be live rendered");
		}

		@Override
		public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> consistent ( ) {
			if ( ! isShared() ) {
				throw new IllegalArgumentException("CONSISTENT updates are only supported for SHARED ReadMoodels.  LIVE, LOCAL and EPHEMERAL ReadModels can not by updated CONSISTENTly.");
			}
			this.consistency = Consistency.CONSISTENT;
			return builder;
		}

		@Override
		public BoundedContextBuilder<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> eventuallyConsistent ( ) {
			this.consistency = Consistency.EVENTUALLY_CONSISTENT;
			return builder;
		}
		
		@Override
		public LongLivedReadModelSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> ephemeral ( ) {
			this.shared = false;
			this.ephemeral = true;
			return this;
		}

		@Override
		public LongLivedReadModelSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> local ( ) {
			this.shared = false;
			this.ephemeral = false;
			return this;
		}
		
		@Override
		public LongLivedReadModelSpecification<DOMAIN_EVENT_TYPE, INBOUND_EVENT_TYPE, OUTBOUND_EVENT_TYPE> shared ( ) {
			this.shared = true;
			this.ephemeral = false;
			return this;
		}

		public boolean isEphemeral ( ) {
			return ephemeral;
		}

		public boolean isShared ( ) {
			return shared;
		}
		
		public boolean isLocal ( ) {
			return !isShared();
		}
		
		public Consistency consistency ( ) {
			return consistency;
		}
		
		public ReadModelWithMetaData<DOMAIN_EVENT_TYPE> readModel ( ) {
			return readModel;
		}
		
	}
	
	private static void logEventTypes ( String type, Class<?> rootEventClass ) {
		LOGGER.info(type + " EVENTS:");
		Class<?>[] eventClasses = (rootEventClass == null ) ? null: rootEventClass.getPermittedSubclasses();
		if ( eventClasses != null ) {
			Arrays.stream(eventClasses).map(ec->"EventType '%s' (%s)".formatted(ec.getSimpleName(), ec.getTypeName())).forEach(LOGGER::info);
		} else {
			LOGGER.info("N/A");
		}
	}
	
	public enum Consistency {
		LIVE,
		CONSISTENT,
		EVENTUALLY_CONSISTENT
	}

}
