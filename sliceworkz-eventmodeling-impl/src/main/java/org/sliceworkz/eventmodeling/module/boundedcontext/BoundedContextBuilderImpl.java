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
import org.sliceworkz.eventmodeling.EventTypes; // retained for javadoc/logging
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateSpecification;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.boundedcontext.AdapterBinding;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener;
import org.sliceworkz.eventmodeling.boundedcontext.FeaturesSpecification;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.module.aggregates.AggregateModule;
import org.sliceworkz.eventmodeling.module.aggregates.AggregateSpecificationImpl;
import org.sliceworkz.eventmodeling.module.automation.AutomationModule;
import org.sliceworkz.eventmodeling.module.dcb.DCBModule;
import org.sliceworkz.eventmodeling.module.inbound.InboundModule;
import org.sliceworkz.eventmodeling.module.outbound.OutboundModule;
import org.sliceworkz.eventmodeling.module.readmodels.LiveModelSpecificationAccessor;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.module.snapshots.LiveModelSnapshotSpecificationImpl;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.LiveModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.LongLivedReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.snapshots.LiveModelSnapshotSpecification;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventmodeling.slices.AnnotationBasedDiscoveryAndConfiguration;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

@SuppressWarnings({"unchecked", "rawtypes"})
public class BoundedContextBuilderImpl<C extends BoundedContext<?,?,?>> implements BoundedContextBuilder<C> {

	static {
		Banner.printBanner();
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(BoundedContextBuilderImpl.class);

	private static final String PURPOSE_DOMAIN = "domain";
	private static final String PURPOSE_INBOUND = "inbound";
	private static final String PURPOSE_OUTBOUND = "outbound";

	private Class<C> contextType;
	private String name;

	private List<LiveModelSpecificationImpl> liveModelSpecs = new ArrayList<>();
	private List<LongLivedReadModelSpecificationImpl> longLivedReadModelSpecs = new ArrayList<>();
	private List<Translator> translatorSpecs = new ArrayList<>();
	private List<Dispatcher> dispatcherSpecs = new ArrayList<>();
	private List<Automation> automations = new ArrayList<>();
	private List<AggregateSpecificationImpl> aggregateSpecifications = new ArrayList<>();

	private Class<?> domainEventRootType;
	private Class<?> inboundEventRootType;
	private Class<?> outboundEventRootType;

	private Class<?> historicalDomainEventRootType;
	private Class<?> historicalInboundEventRootType;
	private Class<?> historicalOutboundEventRootType;

	private FeaturesSpecificationImpl<C> featuresSpecification = new FeaturesSpecificationImpl<>(this);

	private BoundedContextListener boundedContextListener = BoundedContextListener.NO_OP;

	private MeterRegistry meterRegistry = Metrics.globalRegistry;

	private EventStorage eventStorage;

	private Instance instance;

	private final AdapterRegistry adapterRegistry = new AdapterRegistry();

	@Override
	public BoundedContextBuilder<C> contextType(Class<C> contextType) {
		this.contextType = contextType;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> name ( String name ) {
		this.name = name;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> eventTypes (
			Class<?> domainEventRootType,
			Class<?> inboundEventRootType,
			Class<?> outboundEventRootType
			) {
		this.domainEventRootType = domainEventRootType;
		this.inboundEventRootType = inboundEventRootType;
		this.outboundEventRootType = outboundEventRootType;
		 return this;
	}

	@Override
	public BoundedContextBuilder<C> historicalEventTypes (
			Class<?> historicalDomainEventRootType,
			Class<?> historicalInboundEventRootType,
			Class<?> historicalOutboundEventRootType
			) {
		this.historicalDomainEventRootType = historicalDomainEventRootType;
		this.historicalInboundEventRootType = historicalInboundEventRootType;
		this.historicalOutboundEventRootType = historicalOutboundEventRootType;
		return this;
	}

	@Override
	public FeaturesSpecification<C> features ( ) {
		return featuresSpecification;
	}

	@Override
	public BoundedContextBuilder<C> listener ( BoundedContextListener listener ) {
		if ( listener == null ) {
			throw new IllegalArgumentException("listener must not be null");
		}
		this.boundedContextListener = listener;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> instance ( Instance instance ) {
		this.instance = instance;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> meterRegistry ( MeterRegistry meterRegistry ) {
		if ( meterRegistry != null ) {
			this.meterRegistry = meterRegistry;
		} else {
			this.meterRegistry = Metrics.globalRegistry;
		}
		return this;
	}

	@Override
	public BoundedContextBuilder<C> eventStorage ( EventStorage eventStorage ) {
		this.eventStorage = eventStorage;
		return this;
	}

	@Override
	public LiveModelSpecification<C> readmodel ( Class<? extends ReadModelWithMetaData<?>> readModelClass ) {
		var m = new LiveModelSpecificationImpl(this, readModelClass);
		liveModelSpecs.add(m);
		return m;
	}

	@Override
	public LongLivedReadModelSpecification<C> readmodel ( ReadModelWithMetaData<?> readModel ) {
		var m = new LongLivedReadModelSpecificationImpl(this, readModel);
		longLivedReadModelSpecs.add(m);
		return m;
	}

	@Override
	public BoundedContextBuilder<C> automation ( Automation<?,?,?> automation ) {
		automations.add(automation);
		return this;
	}

	@Override
	public BoundedContextBuilder<C> translator ( Translator<?,?> translator ) {
		translatorSpecs.add(translator);
		return this;
	}

	@Override
	public BoundedContextBuilder<C> translator ( Class<? extends Translator<?,?>> translatorClass ) {
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
	public BoundedContextBuilder<C> dispatcher ( Dispatcher<?> dispatcher ) {
		dispatcherSpecs.add(dispatcher);
		return this;
	}

	@Override
	public BoundedContextBuilder<C> dispatcher ( Class<? extends Dispatcher<?>> dispatcherClass ) {
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
	public AggregateSpecification<C> aggregate(Class<? extends Aggregate<?>> aggregateClass) {
		if ( aggregateClass == null ) {
			throw new IllegalArgumentException();
		}
		var aggregateSpecification = new AggregateSpecificationImpl(this, aggregateClass);
		this.aggregateSpecifications.add(aggregateSpecification);
		return aggregateSpecification;
	}

	@Override
	public AdapterBinding<C> adapter(Object adapter) {
		if (adapter == null) {
			throw new IllegalArgumentException("adapter must not be null");
		}
		return new AdapterBindingImpl(adapter);
	}

	@Override
	public <T> T port(Class<T> portType) {
		return adapterRegistry.lookup(portType, AdapterRegistry.DEFAULT_QUALIFICATION);
	}

	@Override
	public <T> T port(Class<T> portType, String qualification) {
		if (qualification == null) {
			throw new IllegalArgumentException("qualification must not be null");
		}
		return adapterRegistry.lookup(portType, qualification);
	}

	@Override
	public C build ( ) {
		Class<?> returnType = contextType != null ? contextType : BoundedContext.class;

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
		EventStream domainEventStream;
		EventStream inboundEventStream;
		EventStream outboundEventStream;

		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage, meterRegistry);
		domainEventStream = historicalDomainEventRootType != null
			? eventStore.getEventStream(EventStreamId.forContext(name).withPurpose(PURPOSE_DOMAIN), domainEventRootType, historicalDomainEventRootType)
			: eventStore.getEventStream(EventStreamId.forContext(name).withPurpose(PURPOSE_DOMAIN), domainEventRootType);
		inboundEventStream = historicalInboundEventRootType != null
			? eventStore.getEventStream(EventStreamId.forContext(name).withPurpose(PURPOSE_INBOUND), inboundEventRootType, historicalInboundEventRootType)
			: eventStore.getEventStream(EventStreamId.forContext(name).withPurpose(PURPOSE_INBOUND), inboundEventRootType);
		outboundEventStream = historicalOutboundEventRootType != null
			? eventStore.getEventStream(EventStreamId.forContext(name).withPurpose(PURPOSE_OUTBOUND), outboundEventRootType, historicalOutboundEventRootType)
			: eventStore.getEventStream(EventStreamId.forContext(name).withPurpose(PURPOSE_OUTBOUND), outboundEventRootType);
		readAllInStoreEventStream = eventStore.getEventStream(EventStreamId.anyContext().anyPurpose());

		List<Slice<C>> deployedFeatureSlices = Collections.emptyList();
		List<Slice<C>> undeployedFeatureSlices = Collections.emptyList();

		if ( featuresSpecification.rootPackage() != null ) {
			deployedFeatureSlices =
			AnnotationBasedDiscoveryAndConfiguration.<Slice<C>>instantiateAndConfigure(
					FeatureSlice.class,
					featuresSpecification.rootPackage(),
					featuresSpecification.filter(),
					slice -> {
							if ( featuresSpecification.mustDeployCommands() ) {
								slice.configureCommand(this);
							}
							if ( featuresSpecification.mustDeployQueries() ) {
								slice.configureQuery(this);
							}
							if ( featuresSpecification.mustDeployAutomations() ) {
								slice.configureAutomation(this);
							}
							if ( featuresSpecification.mustDeployProjections() ) {
								slice.configureProjection(this);
							}
						});

			undeployedFeatureSlices =
			AnnotationBasedDiscoveryAndConfiguration.<Slice<C>>instantiateAndConfigure(
					FeatureSlice.class,
					featuresSpecification.rootPackage(),
					featuresSpecification.filter().negate(),
					fs->{});
		} else {
			LOGGER.warn("no features rootPackage");
		}

		BoundedContextEventEmitter eventEmitter = new BoundedContextEventEmitter(boundedContextListener, instance, new SliceRegistry(deployedFeatureSlices));

		// how each of these is projected (every instance or a single leader) follows from the read
		// model's own storage class, see ReadModelModule.createProjectorProcessors
		Collection<ReadModelWithMetaData> eventuallyConsistentReadModels = longLivedReadModelSpecs.stream().map(LongLivedReadModelSpecificationImpl::readModel).collect(Collectors.toCollection(ArrayList::new));

		Collection<Translator> translators = translatorSpecs.stream().collect(Collectors.toCollection(ArrayList::new));
		InboundModule im = new InboundModule(name, inboundEventStream, translators, instance, meterRegistry);

		Collection<Dispatcher> dispatchers = dispatcherSpecs.stream().collect(Collectors.toCollection(ArrayList::new));
		OutboundModule om = new OutboundModule(name, outboundEventStream, dispatchers, instance, meterRegistry);

		AutomationModule am = new AutomationModule(name, domainEventStream, automations, instance, meterRegistry, eventEmitter);

		ReadModelModule rmm = new ReadModelModule(name, domainEventStream, readAllInStoreEventStream, liveModelSpecs, eventuallyConsistentReadModels, instance, meterRegistry, eventEmitter);
		DCBModule dcb = new DCBModule(name, instance, rmm, domainEventStream, outboundEventStream, meterRegistry, eventEmitter);

		AggregateModule aggregateModule = new AggregateModule(name, instance, aggregateSpecifications, domainEventStream, meterRegistry, eventEmitter);

		BoundedContextImpl bc =
				new BoundedContextImpl(name, deployedFeatureSlices, undeployedFeatureSlices,
						featuresSpecification.mustDeployCommands(),
						featuresSpecification.mustDeployQueries(),
						featuresSpecification.mustDeployAutomations(),
						featuresSpecification.mustDeployProjections(),
						domainEventStream, inboundEventStream, outboundEventStream, eventEmitter, dcb, aggregateModule, rmm, am, im, om, instance, meterRegistry, adapterRegistry);

		// this is only possible after creation
		am.setCapabilitiesDelegate(bc);
		im.setCapabilitiesDelegate(bc);

		C result;
		if ( returnType.isInterface()) {
			result = proxy(bc, returnType);
		} else {
			result = (C)bc;
		}
		bc.setSelfReference((BoundedContext<?,?,?>) result);
		return result;
	}

	public static <T> T proxy ( BoundedContextImpl<?,?,?> boundedContext, Class<?> interfaceClass ) {
		return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[] {interfaceClass}, new InvocationHandler() {
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				try {
					return method.invoke(boundedContext, args);
				} catch (InvocationTargetException e) {
					// unwrap the reflective wrapper so callers see the real exception (e.g. an
					// OptimisticLockingException they can catch and retry) instead of having it
					// re-wrapped by the proxy as an UndeclaredThrowableException
					throw e.getCause();
				}
			}
		});
	}

	public class LiveModelSpecificationImpl implements LiveModelSpecification<C>, LiveModelSpecificationAccessor {

		private BoundedContextBuilder<C> builder;
		private Class<? extends ReadModelWithMetaData<?>> readModelClass;
		private LiveModelSnapshotSpecificationImpl snapshotSpecification;

		public LiveModelSpecificationImpl ( BoundedContextBuilder<C> builder, Class<? extends ReadModelWithMetaData<?>> readModelClass ) {
			this.builder = builder;
			this.readModelClass = readModelClass;
		}

		@Override
		public BoundedContextBuilder<C> live ( ) {
			return builder;
		}

		@Override
		public BoundedContextBuilder<C> eventuallyConsistent ( ) {
			throw new IllegalArgumentException("LIVE read model - cannot be updated EVENTUALLY CONSISTENT");
		}

		@Override
		public <SNAPSHOT_TYPE> LiveModelSnapshotSpecification<C> snapshots (
				SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage ) {
			if ( snapshotStorage == null ) {
				throw new IllegalArgumentException("snapshotStorage can not be null");
			}
			this.snapshotSpecification = new LiveModelSnapshotSpecificationImpl(builder, snapshotStorage);
			return snapshotSpecification;
		}

		public Class<? extends ReadModelWithMetaData<?>> readModelClass ( ) {
			return readModelClass;
		}

		public SnapshotStorage<Object> snapshotStorage ( ) {
			return snapshotSpecification == null ? null : (SnapshotStorage<Object>) snapshotSpecification.snapshotStorage();
		}

		public boolean readSnapshots ( ) {
			return snapshotSpecification != null && snapshotSpecification.readAndOrWrite().mustRead();
		}

		public boolean writeSnapshots ( ) {
			return snapshotSpecification != null && snapshotSpecification.readAndOrWrite().mustWrite();
		}

		public int snapshotEventCountThreshold ( ) {
			return snapshotSpecification == null ? 0 : snapshotSpecification.eventCountThreshold();
		}

	}

	public class LongLivedReadModelSpecificationImpl implements LongLivedReadModelSpecification<C> {

		private BoundedContextBuilder<C> builder;
		private ReadModelWithMetaData<?> readModel;

		public LongLivedReadModelSpecificationImpl ( BoundedContextBuilder<C> builder, ReadModelWithMetaData<?> readModel ) {
			this.builder = builder;
			this.readModel= readModel;
		}

		@Override
		public BoundedContextBuilder<C> live ( ) {
			throw new IllegalArgumentException("state-based readmodel - cannot be live rendered");
		}

		@Override
		public BoundedContextBuilder<C> eventuallyConsistent ( ) {
			return builder;
		}

		public ReadModelWithMetaData<?> readModel ( ) {
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

	private class AdapterBindingImpl implements AdapterBinding<C> {

		private final Object adapter;

		AdapterBindingImpl(Object adapter) {
			this.adapter = adapter;
		}

		@Override
		public <T> BoundedContextBuilder<C> forPort(Class<T> portType) {
			adapterRegistry.register(adapter, portType, AdapterRegistry.DEFAULT_QUALIFICATION);
			return BoundedContextBuilderImpl.this;
		}

		@Override
		public <T> BoundedContextBuilder<C> forPort(Class<T> portType, String qualification) {
			if (qualification == null) {
				throw new IllegalArgumentException("qualification must not be null");
			}
			adapterRegistry.register(adapter, portType, qualification);
			return BoundedContextBuilderImpl.this;
		}
	}

}
