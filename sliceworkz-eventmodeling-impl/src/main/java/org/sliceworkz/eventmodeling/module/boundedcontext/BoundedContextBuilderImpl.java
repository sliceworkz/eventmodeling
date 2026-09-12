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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.Banner;
import org.sliceworkz.eventmodeling.EventTypes; // retained for javadoc/logging
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateSpecification;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.commands.AbstractCommand;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.boundedcontext.AdapterBinding;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextEvent;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextListener;
import org.sliceworkz.eventmodeling.boundedcontext.FeaturesSpecification;
import org.sliceworkz.eventmodeling.boundedcontext.LifecycleCapability;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.module.aggregates.AggregateModule;
import org.sliceworkz.eventmodeling.module.automation.AutomationProcessor;
import org.sliceworkz.eventmodeling.module.eventdispatching.ProjectorProcessor;
import org.sliceworkz.eventmodeling.module.leadership.LeaderElector;
import org.sliceworkz.eventmodeling.module.management.ManagementModule;
import org.sliceworkz.eventmodeling.management.ManagementInstruction;
import org.sliceworkz.eventmodeling.module.aggregates.AggregateSpecificationImpl;
import org.sliceworkz.eventmodeling.module.automation.AutomationModule;
import org.sliceworkz.eventmodeling.module.dcb.DCBModule;
import org.sliceworkz.eventmodeling.module.inbound.InboundModule;
import org.sliceworkz.eventmodeling.module.outbound.OutboundModule;
import org.sliceworkz.eventmodeling.module.readmodels.LiveModelSpecificationAccessor;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.module.snapshots.LiveModelSnapshotSpecificationImpl;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.EventuallyConsistentReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.LiveModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventmodeling.readmodels.SeededReadModel;
import org.sliceworkz.eventmodeling.snapshots.LiveModelSnapshotSpecification;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventmodeling.slices.AnnotationBasedDiscoveryAndConfiguration;
import org.sliceworkz.eventmodeling.slices.Aspect;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.MeterOptions;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
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

	/**
	 * The components every feature slice registers, keyed by slice instance (identity: two slices are
	 * never the same registration even if they compare equal). Filled by {@link #recordSliceMember}
	 * while {@link #configuringSlice} points at the slice whose {@code configure...} methods are
	 * running, which is the only window in which a registration can be attributed to a slice.
	 */
	private final Map<Slice<?>, Set<BoundedContextEvent.SliceMember>> sliceMembers = new IdentityHashMap<>();

	/** The slice currently being configured, or {@code null} outside the configuration callbacks. */
	private Slice<C> configuringSlice;

	/**
	 * The aspect currently being configured, which is what a registration is attributed to. Knowing it
	 * is the whole point of attributing during the callbacks: the same read model registered from
	 * {@code configureQuery} and from {@code configureProjection} is two members that run in different
	 * places, and only the callback in progress tells them apart.
	 */
	private Aspect configuringAspect;

	private List<LiveModelSpecificationImpl> liveModelSpecs = new ArrayList<>();
	private List<EventuallyConsistentReadModelSpecificationImpl> eventuallyConsistentReadModelSpecs = new ArrayList<>();
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
	private EventStream<ManagementInstruction> managementInstructions;

	private MeterRegistry meterRegistry = Metrics.globalRegistry;
	private MeterOptions meterOptions = MeterOptions.defaults();

	private EventStorage eventStorage;
	private ShreddingCodec shreddingCodec;

	private Instance instance;

	private long leadershipPriority = 0;
	private Duration leadershipHeartbeat = Duration.ofSeconds(5);
	private Duration leadershipTtl = Duration.ofSeconds(20);

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
	public BoundedContextBuilder<C> management ( EventStream<ManagementInstruction> instructions ) {
		if ( instructions == null ) {
			throw new IllegalArgumentException("instructions stream must not be null");
		}
		this.managementInstructions = instructions;
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
	public BoundedContextBuilder<C> meterOptions ( MeterOptions meterOptions ) {
		this.meterOptions = meterOptions == null ? MeterOptions.defaults() : meterOptions;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> shredding ( ShreddingKeyStore shreddingKeyStore ) {
		if ( shreddingKeyStore == null ) {
			throw new IllegalArgumentException("shreddingKeyStore cannot be null");
		}
		// The shipped codec is applied here so a caller choosing where keys live never has to know
		// anything about the cryptography; shredding(ShreddingCodec) is the seam for replacing it.
		this.shreddingCodec = AesGcmShreddingCodec.over(shreddingKeyStore);
		return this;
	}

	@Override
	public BoundedContextBuilder<C> shredding ( ShreddingCodec shreddingCodec ) {
		if ( shreddingCodec == null ) {
			throw new IllegalArgumentException("shreddingCodec cannot be null");
		}
		this.shreddingCodec = shreddingCodec;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> eventStorage ( EventStorage eventStorage ) {
		this.eventStorage = eventStorage;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> leadershipPriority ( long priority ) {
		this.leadershipPriority = priority;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> leadershipIntervals ( Duration heartbeat, Duration ttl ) {
		if ( heartbeat == null || heartbeat.isNegative() || heartbeat.isZero() ) {
			throw new IllegalArgumentException("leadership heartbeat must be positive, but was " + heartbeat);
		}
		if ( ttl == null || ttl.compareTo(heartbeat.multipliedBy(2)) < 0 ) {
			// a ttl under two heartbeats makes a single failed renewal cost leadership, which turns
			// every hiccup into a failover
			throw new IllegalArgumentException("leadership ttl must be at least twice the heartbeat (%s), but was %s".formatted(heartbeat, ttl));
		}
		this.leadershipHeartbeat = heartbeat;
		this.leadershipTtl = ttl;
		return this;
	}

	@Override
	public LiveModelSpecification<C> readmodel ( Class<? extends ReadModelWithMetaData<?>> readModelClass ) {
		var m = new LiveModelSpecificationImpl(this, readModelClass);
		liveModelSpecs.add(m);
		// A live model is instantiated per projection, so only its class is known here. That matches
		// the name the LiveModelProjected events carry unless the read model overrides readmodelName().
		recordSliceMember(readModelClass.getSimpleName(), BoundedContextEvent.MemberKind.READ_MODEL);
		return m;
	}

	@Override
	public EventuallyConsistentReadModelSpecification<C> readmodel ( ReadModelWithMetaData<?> readModel ) {
		// A seed says where a *read* resumes projecting, and only the live path ever asks for one. An
		// eventually consistent processor resumes from its bookmark (or from SelfBookmarkingProjection),
		// so this registration would leave seed() never called and the read model silently ordinary.
		if ( readModel instanceof SeededReadModel<?> ) {
			throw new IllegalArgumentException(("%s is a SeededReadModel, which is projected per read: register it as "
					+ "readmodel(%s.class).live(). An eventually consistent read model resumes from its bookmark and would never call seed()")
					.formatted(readModel.readmodelName(), readModel.getClass().getSimpleName()));
		}
		var m = new EventuallyConsistentReadModelSpecificationImpl(this, readModel);
		eventuallyConsistentReadModelSpecs.add(m);
		recordSliceMember(readModel.readmodelName(), BoundedContextEvent.MemberKind.READ_MODEL);
		return m;
	}

	@Override
	public BoundedContextBuilder<C> automation ( Automation<?,?,?> automation ) {
		automations.add(automation);
		recordSliceMember(automation.getClass().getSimpleName(), BoundedContextEvent.MemberKind.AUTOMATION);
		return this;
	}

	@Override
	public BoundedContextBuilder<C> translator ( Translator<?,?> translator ) {
		translatorSpecs.add(translator);
		recordSliceMember(translator.getClass().getSimpleName(), BoundedContextEvent.MemberKind.TRANSLATOR);
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
		recordSliceMember(dispatcher.getClass().getSimpleName(), BoundedContextEvent.MemberKind.DISPATCHER);
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
	public BoundedContextBuilder<C> command ( Class<?>... commandClasses ) {
		if ( commandClasses == null ) {
			throw new IllegalArgumentException("commandClasses must not be null");
		}
		for ( Class<?> commandClass : commandClasses ) {
			if ( commandClass == null ) {
				throw new IllegalArgumentException("command class must not be null");
			}
			if ( !AbstractCommand.class.isAssignableFrom(commandClass) && !CommandWithResult.class.isAssignableFrom(commandClass) ) {
				throw new IllegalArgumentException("%s is not a command: it implements neither %s nor %s"
						.formatted(commandClass.getName(), AbstractCommand.class.getSimpleName(), CommandWithResult.class.getSimpleName()));
			}
			// Nothing to wire: a command is instantiated by the caller and executed ad hoc. This only
			// declares it, so its slice reports it before it has ever run.
			recordSliceMember(AbstractCommand.commandNameOf(commandClass), BoundedContextEvent.MemberKind.COMMAND);
		}
		return this;
	}

	@Override
	public AggregateSpecification<C> aggregate(Class<? extends Aggregate<?>> aggregateClass) {
		if ( aggregateClass == null ) {
			throw new IllegalArgumentException();
		}
		var aggregateSpecification = new AggregateSpecificationImpl(this, aggregateClass);
		this.aggregateSpecifications.add(aggregateSpecification);
		recordSliceMember(aggregateClass.getSimpleName(), BoundedContextEvent.MemberKind.AGGREGATE);
		return aggregateSpecification;
	}

	/** Runs one of a slice's configuration callbacks, marking what registers during it as that aspect's. */
	private void configure ( Aspect aspect, Runnable configuration ) {
		configuringAspect = aspect;
		try {
			configuration.run();
		} finally {
			configuringAspect = null;
		}
	}

	/**
	 * Attributes a registration to the feature slice and aspect currently being configured, so
	 * {@link BoundedContextEvent.FeatureSlice#members()} can announce what a slice is made of, and
	 * which part of it each member belongs to, before any of it has run. Registrations made outside a
	 * slice's configuration (directly on the builder) belong to no slice and are ignored.
	 */
	private void recordSliceMember ( String name, BoundedContextEvent.MemberKind kind ) {
		if ( configuringSlice != null && name != null ) {
			sliceMembers.computeIfAbsent(configuringSlice, slice -> new LinkedHashSet<>())
					.add(new BoundedContextEvent.SliceMember(name, kind, configuringAspect));
		}
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

	/**
	 * Rejects a read model registered without saying how it is to be projected.
	 * <p>
	 * The two modes are not interchangeable — one is projected per read and always current, the other
	 * in the background and behind by up to a poll interval — and which one a read model wants is a
	 * decision with consequences for every caller. {@code readmodel(...)} used to pick one silently
	 * from the overload, so a registration could be written without the choice ever being made, and
	 * read later without it being visible. Saying it is now the price of registering.
	 * <p>
	 * Note that the registration itself stays eager: the read model is registered by
	 * {@code readmodel(...)} as it always was, and what is missing here is the statement of intent, not
	 * the read model. Registering lazily from {@code live()} instead would turn a forgotten verb into a
	 * read model that silently is not there — trading a mistake this catches at build time for one that
	 * surfaces as a failing read.
	 */
	private void rejectReadModelsWithoutAChosenMode ( ) {
		List<String> undeclared = new ArrayList<>();
		for ( LiveModelSpecificationImpl spec : liveModelSpecs ) {
			if ( !spec.modeChosen() ) {
				undeclared.add("%s (add .live(), or .snapshots(...) to seed it from one)".formatted(spec.readModelClass().getSimpleName()));
			}
		}
		for ( EventuallyConsistentReadModelSpecificationImpl spec : eventuallyConsistentReadModelSpecs ) {
			if ( !spec.modeChosen() ) {
				undeclared.add("%s (add .eventuallyConsistent())".formatted(spec.readModel().readmodelName()));
			}
		}
		if ( !undeclared.isEmpty() ) {
			throw new IllegalArgumentException(
					"readmodel registered without saying how it is projected: " + String.join(", ", undeclared));
		}
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

		rejectReadModelsWithoutAChosenMode();

		logEventTypes("DOMAIN", domainEventRootType);
		logEventTypes("INBOUND", inboundEventRootType);
		logEventTypes("OUTBOUND", outboundEventRootType);

		// The four-argument factory, so that a context configured with shredding hands its codec to the
		// store: it is what seals Shreddable values on append, unseals them on read, and holds the keys
		// that erase() destroys. A null codec is the unprotected store this call has always built.
		EventStore eventStore = EventStoreFactory.get()
				.eventStore(eventStorage, meterRegistry, meterOptions, shreddingCodec);

		// Everything from here on belongs to a context that does not exist yet. A builder that throws
		// hands the caller nothing -- no context, so no terminate() and no shutdown hook -- so whatever
		// was constructed by then is unreachable and would never be released. This is not a hypothetical
		// path: the four registries reject a duplicate or anonymous component name from the middle of
		// the sequence below, and the modules built before the rejection have already subscribed their
		// processors to their streams, which the store holds until it is closed.
		List<LifecycleCapability> constructed = new ArrayList<>();
		try {
			return assemble(returnType, eventStore, constructed);
		} catch ( RuntimeException | Error failed ) {
			releasePartiallyBuilt(constructed, eventStore, failed);
			throw failed;
		}
	}

	/**
	 * Releases what a failed {@link #build()} had already constructed, attaching anything that goes
	 * wrong here to the failure being reported rather than replacing it: the caller needs to see why
	 * the build failed, not why the cleanup after it did.
	 */
	private static void releasePartiallyBuilt ( List<LifecycleCapability> constructed, EventStore eventStore, Throwable failure ) {
		for ( int i = constructed.size() - 1; i >= 0; i-- ) { // reverse order of construction
			try {
				constructed.get(i).terminate();
			} catch ( RuntimeException | Error cleanupFailed ) {
				failure.addSuppressed(cleanupFailed);
			}
		}
		try {
			// ours, built over the storage we were handed -- so it is ours to close, and closing it
			// releases the streams the processors above subscribed to. The storage stays open: it came
			// from outside and can back other contexts.
			eventStore.close();
		} catch ( RuntimeException | Error cleanupFailed ) {
			failure.addSuppressed(cleanupFailed);
		}
	}

	private C assemble ( Class<?> returnType, EventStore eventStore, List<LifecycleCapability> constructed ) {

		EventStream<Object> readAllInStoreEventStream;
		EventStream domainEventStream;
		EventStream inboundEventStream;
		EventStream outboundEventStream;

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
							// Everything registered while this is set is attributed to this slice and to the
							// aspect being configured, which is how a slice can announce what it is made of,
							// and where each part of it runs, before it has done any work.
							configuringSlice = slice;
							try {
								if ( featuresSpecification.mustDeployCommands() ) {
									configure(Aspect.COMMAND, () -> slice.configureCommand(this));
								}
								if ( featuresSpecification.mustDeployQueries() ) {
									configure(Aspect.QUERY, () -> slice.configureQuery(this));
								}
								if ( featuresSpecification.mustDeployAutomations() ) {
									configure(Aspect.AUTOMATION, () -> slice.configureAutomation(this));
								}
								if ( featuresSpecification.mustDeployProjections() ) {
									configure(Aspect.PROJECTION, () -> slice.configureProjection(this));
								}
							} finally {
								configuringSlice = null;
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

		BoundedContextEventEmitter eventEmitter = new BoundedContextEventEmitter(boundedContextListener, instance, new SliceRegistry(deployedFeatureSlices, sliceMembers), name, meterRegistry);

		// how each of these is projected (every instance or a single leader) follows from the read
		// model's own storage class, see ReadModelModule.createProjectorProcessors
		Collection<ReadModelWithMetaData> eventuallyConsistentReadModels = eventuallyConsistentReadModelSpecs.stream().map(EventuallyConsistentReadModelSpecificationImpl::readModel).collect(Collectors.toCollection(ArrayList::new));

		// each module is recorded as soon as it exists, so a failure in the next one still finds it --
		// see releasePartiallyBuilt
		Collection<Translator> translators = translatorSpecs.stream().collect(Collectors.toCollection(ArrayList::new));
		InboundModule im = new InboundModule(name, inboundEventStream, translators, instance, meterRegistry, eventEmitter);
		constructed.add(im);

		Collection<Dispatcher> dispatchers = dispatcherSpecs.stream().collect(Collectors.toCollection(ArrayList::new));
		OutboundModule om = new OutboundModule(name, outboundEventStream, dispatchers, instance, meterRegistry, eventEmitter);
		constructed.add(om);

		AutomationModule am = new AutomationModule(name, domainEventStream, automations, instance, meterRegistry, eventEmitter);
		constructed.add(am);

		ReadModelModule rmm = new ReadModelModule(name, domainEventStream, readAllInStoreEventStream, liveModelSpecs, eventuallyConsistentReadModels, instance, meterRegistry, eventEmitter);
		constructed.add(rmm);
		DCBModule dcb = new DCBModule(name, instance, rmm, domainEventStream, outboundEventStream, meterRegistry, eventEmitter);
		constructed.add(dcb);

		AggregateModule aggregateModule = new AggregateModule(name, instance, aggregateSpecifications, domainEventStream, meterRegistry, eventEmitter);

		// one lease per leader-only processor, named by its ProcessorIdentification: an instance only
		// contends for the elements it has deployed, so heterogeneous deployments elect per element.
		// The owner is Instance.process() -- fresh per JVM run, so a restarted process is a new
		// contender and its predecessor's leases expire instead of being confusingly renewed.
		List<LeaderElector.Electable> electables = new ArrayList<>();
		im.leaderOnlyProcessors().forEach(p -> electables.add(new LeaderElector.Electable(((ProjectorProcessor<?>) p).identification(), (ProjectorProcessor<?>) p)));
		om.leaderOnlyProcessors().forEach(p -> electables.add(new LeaderElector.Electable(((ProjectorProcessor<?>) p).identification(), (ProjectorProcessor<?>) p)));
		am.leaderOnlyProcessors().forEach(p -> electables.add(new LeaderElector.Electable(((AutomationProcessor<?,?,?>) p).identification(), (AutomationProcessor<?,?,?>) p)));
		rmm.leaderOnlyProcessors().forEach(p -> electables.add(new LeaderElector.Electable(((ProjectorProcessor<?>) p).identification(), (ProjectorProcessor<?>) p)));
		LeaderElector leaderElector = new LeaderElector(name, eventStorage, instance.process(),
				leadershipPriority, leadershipHeartbeat, leadershipTtl, electables, eventEmitter);

		// the operator's channel in, if one was given: subscribed by start(), released by terminate()
		ManagementModule managementModule = managementInstructions == null
				? null
				: new ManagementModule(name, instance, managementInstructions, eventEmitter);

		BoundedContextImpl bc =
				new BoundedContextImpl(name, deployedFeatureSlices, undeployedFeatureSlices,
						featuresSpecification.mustDeployCommands(),
						featuresSpecification.mustDeployQueries(),
						featuresSpecification.mustDeployAutomations(),
						featuresSpecification.mustDeployProjections(),
						eventStore,
						domainEventStream, inboundEventStream, outboundEventStream, eventEmitter, dcb, aggregateModule, rmm, am, im, om, leaderElector, managementModule, instance, meterRegistry, adapterRegistry);

		// From here the context owns the modules, and it is the only thing that can release them
		// completely: its constructor registered a JVM shutdown hook holding it, which only its own
		// terminate() deregisters. So a failure in the little that is left must go through the context
		// rather than through the modules directly.
		constructed.clear();
		constructed.add(bc);

		// this is only possible after creation
		am.setCapabilitiesDelegate(bc);
		im.setCapabilitiesDelegate(bc);
		if ( managementModule != null ) {
			managementModule.attach(bc);
		}

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
		// whether the caller has said how this read model is projected -- see rejectReadModelsWithoutAChosenMode
		private boolean modeChosen;

		public LiveModelSpecificationImpl ( BoundedContextBuilder<C> builder, Class<? extends ReadModelWithMetaData<?>> readModelClass ) {
			this.builder = builder;
			this.readModelClass = readModelClass;
		}

		@Override
		public BoundedContextBuilder<C> live ( ) {
			this.modeChosen = true;
			return builder;
		}

		@Override
		public BoundedContextBuilder<C> eventuallyConsistent ( ) {
			throw new IllegalArgumentException("LIVE read model - cannot be updated EVENTUALLY CONSISTENT");
		}

		public boolean modeChosen ( ) {
			return modeChosen;
		}

		@Override
		public <SNAPSHOT_TYPE> LiveModelSnapshotSpecification<C> snapshots (
				SnapshotStorage<SNAPSHOT_TYPE> snapshotStorage ) {
			if ( snapshotStorage == null ) {
				throw new IllegalArgumentException("snapshotStorage can not be null");
			}
			// Both answer the same question -- where does this projection start -- and there is no
			// sensible precedence between them: a snapshot store and the read model's own base would
			// each be a complete answer, silently disagreeing about how much history was skipped.
			if ( SeededReadModel.class.isAssignableFrom(readModelClass) ) {
				throw new IllegalArgumentException(("%s is a SeededReadModel and loads its own base, so it cannot also be "
						+ "registered with snapshots(): pick one of the two")
						.formatted(readModelClass.getSimpleName()));
			}
			// asking for snapshots is itself a statement that this read model is projected per read
			this.modeChosen = true;
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

	public class EventuallyConsistentReadModelSpecificationImpl implements EventuallyConsistentReadModelSpecification<C> {

		private BoundedContextBuilder<C> builder;
		private ReadModelWithMetaData<?> readModel;
		// whether the caller has said how this read model is projected -- see rejectReadModelsWithoutAChosenMode
		private boolean modeChosen;

		public EventuallyConsistentReadModelSpecificationImpl ( BoundedContextBuilder<C> builder, ReadModelWithMetaData<?> readModel ) {
			this.builder = builder;
			this.readModel= readModel;
		}

		@Override
		public BoundedContextBuilder<C> live ( ) {
			throw new IllegalArgumentException("state-based readmodel - cannot be live rendered");
		}

		@Override
		public BoundedContextBuilder<C> eventuallyConsistent ( ) {
			this.modeChosen = true;
			return builder;
		}

		public boolean modeChosen ( ) {
			return modeChosen;
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
