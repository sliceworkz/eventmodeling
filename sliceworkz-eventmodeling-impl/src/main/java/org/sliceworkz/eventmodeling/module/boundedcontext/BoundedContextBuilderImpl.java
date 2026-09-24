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
import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
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
import org.sliceworkz.eventmodeling.observability.BoundedContextObserver;
import org.sliceworkz.eventmodeling.module.aggregates.AggregateSpecificationImpl;
import org.sliceworkz.eventmodeling.module.automation.AutomationModule;
import org.sliceworkz.eventmodeling.module.dcb.DCBModule;
import org.sliceworkz.eventmodeling.module.inbound.InboundModule;
import org.sliceworkz.eventmodeling.module.outbound.OutboundModule;
import org.sliceworkz.eventmodeling.module.readmodels.LiveModelConstructors;
import org.sliceworkz.eventmodeling.module.readmodels.LiveModelSpecificationAccessor;
import org.sliceworkz.eventmodeling.module.readmodels.ReadModelModule;
import org.sliceworkz.eventmodeling.module.snapshots.LiveModelSnapshotSpecificationImpl;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.EventuallyConsistentReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.LiveModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventmodeling.readmodels.SeededReadModel;
import org.sliceworkz.eventmodeling.snapshots.LiveModelSnapshotSpecification;
import org.sliceworkz.eventmodeling.snapshots.SnapshotStorage;
import org.sliceworkz.eventmodeling.slices.AnnotationBasedDiscoveryAndConfiguration;
import org.sliceworkz.eventmodeling.slices.Aspect;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.observability.EventStoreObserver;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;


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

	private BoundedContextObserver observer = BoundedContextObserver.NOOP;
	/** Null until set: the store then reports to the storage's own observer. */
	private EventStoreObserver eventStoreObserver;

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
	public BoundedContextBuilder<C> observer ( BoundedContextObserver observer ) {
		if ( observer == null ) {
			throw new IllegalArgumentException("observer cannot be null.  Use BoundedContextObserver.NOOP to observe nothing");
		}
		this.observer = observer;
		return this;
	}

	@Override
	public BoundedContextBuilder<C> eventStoreObserver ( EventStoreObserver eventStoreObserver ) {
		if ( eventStoreObserver == null ) {
			throw new IllegalArgumentException("event store observer cannot be null.  Leave it unset for the storage's own observer");
		}
		this.eventStoreObserver = eventStoreObserver;
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
	public LiveModelSpecification<C> readmodel ( Class<? extends ReadModel<?>> readModelClass ) {
		var m = new LiveModelSpecificationImpl(this, readModelClass);
		liveModelSpecs.add(m);
		// A live model is instantiated per projection, so only its class is known here. That matches
		// the name the LiveModelProjected events carry unless the read model overrides readmodelName().
		recordSliceMember(readModelClass.getSimpleName(), BoundedContextEvent.MemberKind.READ_MODEL);
		return m;
	}

	@Override
	public EventuallyConsistentReadModelSpecification<C> readmodel ( ReadModel<?> readModel ) {
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
		return translator(instantiate(translatorClass, "Translator"));
	}

	@Override
	public BoundedContextBuilder<C> dispatcher ( Dispatcher<?> dispatcher ) {
		dispatcherSpecs.add(dispatcher);
		recordSliceMember(dispatcher.getClass().getSimpleName(), BoundedContextEvent.MemberKind.DISPATCHER);
		return this;
	}

	@Override
	public BoundedContextBuilder<C> dispatcher ( Class<? extends Dispatcher<?>> dispatcherClass ) {
		return dispatcher(instantiate(dispatcherClass, "Dispatcher"));
	}

	/**
	 * The instance the {@code translator(Class)} / {@code dispatcher(Class)} overloads register, or an
	 * {@link IllegalArgumentException} naming the class, the kind it was registered as, and what it
	 * would take to make it instantiable.
	 * <p>
	 * Registering by class is registering a class this framework constructs, so every way that can fail
	 * is a property of the declaration and belongs with the other registration rejections: a missing (or
	 * non-public) no-argument constructor -- what an inner class that should have been {@code static}
	 * produces -- an abstract class or an interface registered where an implementation was meant, and a
	 * constructor that ran and threw. The alternative -- a bare {@code RuntimeException} around each
	 * reflective exception -- loses because such a failure carries no message at all: a stack trace
	 * naming neither the component nor the reason, with a constructor's own throwable a cause deeper
	 * still than the reflective wrapper. The wording follows the eventstore's upcaster instantiation,
	 * which is the same mistake one layer down.
	 */
	private static <T> T instantiate ( Class<? extends T> componentClass, String kind ) {
		try {
			return componentClass.getDeclaredConstructor().newInstance();
		} catch (InvocationTargetException e) {
			// the constructor ran and threw: report what it threw, not the reflective wrapper
			throw new IllegalArgumentException(
					"%s %s threw from its no-argument constructor: %s".formatted(
							kind, componentClass.getName(), e.getTargetException()),
					e.getTargetException());
		} catch (ReflectiveOperationException e) {
			// NoSuchMethod (no no-arg constructor -- an inner class needs to be static), Instantiation
			// (abstract or an interface) or IllegalAccess (not public). All three are "the class
			// registered here cannot be instantiated", and the remedy is the same sentence
			throw new IllegalArgumentException(
					("%s %s cannot be instantiated: %s. Registering by class needs a public no-argument "
							+ "constructor on a concrete, non-inner (or static nested) class -- register an "
							+ "instance instead where the component takes constructor arguments.")
							.formatted(kind, componentClass.getName(), e),
					e);
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

	/**
	 * Rejects a live read model class no read of it could ever instantiate.
	 * <p>
	 * A live read model is constructed per read, so the class is registered here and the parameters
	 * arrive later — which means most of what can go wrong between the two is a property of the read and
	 * belongs there ({@link org.sliceworkz.eventmodeling.module.readmodels.LiveModelConstructors#select}
	 * weighs the parameter types and names what it refused). Two things are not: a class that is abstract
	 * — an interface or a base class registered where an implementation was meant — and one declaring no
	 * constructor the framework can reach. Neither depends on what a read passes, so every read of such a
	 * class fails, and catching that at build time is the same trade as
	 * {@link #rejectReadModelsWithoutAChosenMode}: a mistake named where it was made rather than one
	 * surfacing as a failing read, possibly on a path nothing exercises until production.
	 * <p>
	 * What is deliberately <i>not</i> checked here is ambiguity between two constructors of one arity.
	 * Whether two constructors are ambiguous depends on the arguments — this repository's own
	 * {@code MockReadModel} declares {@code (String, List)} beside {@code (String, ReadModelStorage)},
	 * which no read can confuse — so a build-time rejection of same-arity constructors would refuse read
	 * models that are perfectly resolvable. The tie is refused at the read, where the arguments are
	 * known and the message can name them.
	 * <p>
	 * Eventually consistent read models are registered as instances, so they are already constructed and
	 * have nothing to check.
	 */
	private void rejectLiveReadModelsThatCannotBeInstantiated ( ) {
		List<String> offenders = new ArrayList<>();
		for ( LiveModelSpecificationImpl spec : liveModelSpecs ) {
			String reason = LiveModelConstructors.uninstantiableReason(spec.readModelClass());
			if ( reason != null ) {
				offenders.add("%s (%s)".formatted(spec.readModelClass().getSimpleName(), reason));
			}
		}
		if ( !offenders.isEmpty() ) {
			throw new IllegalArgumentException(
					"live readmodel registered that no read could construct: " + String.join(", ", offenders));
		}
	}

	/**
	 * Rejects an automation whose todo list nobody in this process will project.
	 * <p>
	 * An automation handles nothing until the projector filling its todo list has bookmarked past the
	 * last event the automation produced, and that projector exists only where the todo list is
	 * registered: {@code builder.readmodel(todoList).eventuallyConsistent()}. Registering the automation
	 * alone leaves it waiting on a bookmark nobody writes -- no exception, no bounded-context event, one
	 * WARN line that a cold start produces too -- which is the silent shape this catches at build time,
	 * as {@link #rejectReadModelsWithoutAChosenMode} catches a read model whose mode was never chosen.
	 * <p>
	 * The check is by {@code readmodelName()} and storage class, since that is what identifies the
	 * bookmark the automation waits on (see {@code AutomationModule.createAutomationProcessors}): the
	 * todo list registered need not be the instance the automation holds, and a registration under the
	 * same name with another storage class names a different bookmark and is as much a miss.
	 * <p>
	 * Only an {@link ReadModelStorage#EPHEMERAL} or {@link ReadModelStorage#LOCAL} todo list is checked.
	 * Their bookmark id carries this instance's location, so nothing outside this process can ever write
	 * it and a missing registration here is a proof. A {@link ReadModelStorage#SHARED} todo list's
	 * bookmark is deployment-wide and its projector holds a lease of its own, so an instance may
	 * legitimately run the automation while another projects the list it reads; for those the
	 * automation's runtime warning stays the signal. The alternative -- requiring the registration for
	 * every storage class, on the rule that an automation deploys together with its todo list -- loses
	 * because that rule cannot be imposed on a deployment, and a check that rejects a legitimate one is
	 * worse than none.
	 */
	private void rejectAutomationsWhoseTodoListIsNotProjectedHere ( ) {
		List<String> unprojected = new ArrayList<>();
		for ( Automation<?,?,?> automation : automations ) {
			String automationName = automation.getClass().getSimpleName();
			TodoListReadModel<?,?> todoList = automation.getTodoList();
			if ( todoList == null ) {
				unprojected.add("%s (getTodoList() returned null)".formatted(automationName));
				continue;
			}
			ReadModelStorage storage = todoList.storage();
			if ( !storage.projectedOnEveryInstance() ) {
				continue;
			}
			String name = todoList.readmodelName();
			ReadModel<?> registered = eventuallyConsistentReadModelSpecs.stream()
					.map(EventuallyConsistentReadModelSpecificationImpl::readModel)
					.filter(rm -> name.equals(rm.readmodelName()))
					.findFirst().orElse(null);
			if ( registered == null ) {
				unprojected.add("%s (its todo list '%s' is %s and not registered: add builder.readmodel(todoList).eventuallyConsistent())"
						.formatted(automationName, name, storage.label()));
			} else if ( registered.storage() != storage ) {
				unprojected.add("%s (its todo list '%s' is %s, but the read model registered under that name is %s: they bookmark under different ids)"
						.formatted(automationName, name, storage.label(), registered.storage().label()));
			}
		}
		if ( !unprojected.isEmpty() ) {
			throw new IllegalArgumentException(
					"automation registered without a projector for its todo list on this instance: " + String.join(", ", unprojected));
		}
	}

	/**
	 * Whether a scanned {@code @FeatureSlice} class is a slice of <em>this</em> bounded context.
	 * <p>
	 * A slice declares the context it belongs to — {@code class OpenAccountFeatureSlice implements
	 * Slice<Banking>} — and the scan hands back a bare {@code Class}, so the cast to {@code Slice<C>}
	 * is unchecked and nothing at compile time or at runtime held a slice to the context it named. Two
	 * bounded contexts whose slices share a root package therefore each discovered the other's: the
	 * foreign slices' {@code configure...} methods ran against this builder, registering the other
	 * context's read models, automations and commands here, and its {@code start...} methods were
	 * handed a context of a type they do not accept.
	 * <p>
	 * <b>A slice that is not this context's is dropped, not rejected.</b> One package holding several
	 * contexts' slices is an ordinary layout — the banking examples are exactly that — so "not mine" is
	 * a filter, not a mistake. It keeps them out of both inventories as well: a slice of another
	 * context is not an undeployed slice of this one, it is none of this context's business. What a
	 * mistyped slice costs is therefore silence, which is why the skip is logged (at DEBUG, since a
	 * package deliberately shared between contexts would otherwise log every other context's slices on
	 * every build).
	 * <p>
	 * The test is assignability rather than equality, and in that direction: a slice is handed this
	 * context through {@code startCommand(C)} and friends, so it qualifies exactly when the type it
	 * declared can accept one — a slice declared over a supertype of this context serves it, one
	 * declared over a sibling or a subtype cannot. A declaration that fixes no context at all (a raw
	 * {@code Slice}) is not evidence that it belongs elsewhere, and is kept, as is every slice when
	 * nothing said what this context's type is.
	 */
	private boolean isSliceOfThisContext ( Class<?> sliceClass ) {
		if ( contextType == null ) {
			return true;
		}
		Class<?> declared = TypeArguments.of(sliceClass, Slice.class, 0);
		if ( declared == null || declared.isAssignableFrom(contextType) ) {
			return true;
		}
		LOGGER.debug("feature slice {} declares Slice<{}>, which cannot accept this context's {} -- not deployed here",
				sliceClass.getSimpleName(), declared.getSimpleName(), contextType.getSimpleName());
		return false;
	}

	/**
	 * Rejects a component registered over another bounded context's event types.
	 * <p>
	 * Every registration on this builder is wildcard-typed — {@code readmodel(Class<? extends
	 * ReadModel<?>>)}, {@code automation(Automation<?,?,?>)}, {@code translator(Translator<?,?>)} and
	 * the rest — so the compiler admits a read model of the payments context on the banking one. It
	 * cannot do otherwise: the builder is typed by the context alone, and Java offers no way to project
	 * {@code D}, {@code I} and {@code O} back out of {@code C extends BoundedContext<D,I,O>}. The
	 * alternative — carrying all four as type parameters, {@code BoundedContextBuilder<C,D,I,O>} —
	 * would put the check in the compiler, and loses because that quartet then has to be spelled out in
	 * every {@code Slice} signature a user writes, where a single context type reads as what it is.
	 * <p>
	 * So the builder checks it itself, from the root types {@code newBuilder} already resolved off the
	 * context interface. Nothing further down does: the framework hands the component its events through
	 * an erased {@code Projection}, and the component's own {@code eventQuery()} names types that never
	 * occur on this context's stream — so the query matches nothing and the read model, dispatcher or
	 * todo list simply stays empty, for good, with no exception, no bounded-context event and no log
	 * line. That is the same silent shape {@link #rejectAutomationsWhoseTodoListIsNotProjectedHere}
	 * catches, and it is caught here for the same reason.
	 * <p>
	 * <b>Only unrelated types are refused.</b> A component declared over a supertype of this context's
	 * root is legitimate — a read model over {@code Object} projects whatever it is handed, which is what
	 * an analytics model across contexts does — and so is one declared over a branch of the hierarchy,
	 * whose {@code eventQuery()} is what keeps the other branches away from it. Neither can be told from
	 * a mistake here, and a check that rejects a legitimate registration is worse than none. What no
	 * declaration and no query can make sensible is a component whose event type and this context's have
	 * nothing to do with each other, which is exactly the registration this names.
	 * <p>
	 * A declaration that fixes no event class at all — a raw implementation, a type variable left open —
	 * is not evidence of the wrong one and passes, as does a check whose root type was never set.
	 */
	private void rejectComponentsOfAnotherContextsEventTypes ( ) {
		List<String> foreign = new ArrayList<>();
		for ( LiveModelSpecificationImpl spec : liveModelSpecs ) {
			Class<?> readModelClass = spec.readModelClass();
			checkEventType(foreign, "readmodel", readModelClass.getSimpleName(),
					readModelClass, ReadModel.class, 0, "domain", domainEventRootType);
		}
		for ( EventuallyConsistentReadModelSpecificationImpl spec : eventuallyConsistentReadModelSpecs ) {
			ReadModel<?> readModel = spec.readModel();
			checkEventType(foreign, "readmodel", readModel.readmodelName(),
					readModel.getClass(), ReadModel.class, 0, "domain", domainEventRootType);
		}
		for ( AggregateSpecificationImpl spec : aggregateSpecifications ) {
			Class<?> aggregateClass = spec.aggregateClass();
			checkEventType(foreign, "aggregate", aggregateClass.getSimpleName(),
					aggregateClass, Aggregate.class, 0, "domain", domainEventRootType);
		}
		for ( Automation<?,?,?> automation : automations ) {
			String name = automation.getClass().getSimpleName();
			checkEventType(foreign, "automation", name,
					automation.getClass(), Automation.class, 1, "domain", domainEventRootType);
			checkEventType(foreign, "automation", name,
					automation.getClass(), Automation.class, 2, "outbound", outboundEventRootType);
		}
		for ( Translator<?,?> translator : translatorSpecs ) {
			String name = translator.getClass().getSimpleName();
			checkEventType(foreign, "translator", name,
					translator.getClass(), Translator.class, 0, "inbound", inboundEventRootType);
			checkEventType(foreign, "translator", name,
					translator.getClass(), Translator.class, 1, "domain", domainEventRootType);
		}
		for ( Dispatcher<?> dispatcher : dispatcherSpecs ) {
			checkEventType(foreign, "dispatcher", dispatcher.getClass().getSimpleName(),
					dispatcher.getClass(), Dispatcher.class, 0, "outbound", outboundEventRootType);
		}
		if ( !foreign.isEmpty() ) {
			throw new IllegalArgumentException(
					"component registered over another bounded context's event types: " + String.join(", ", foreign));
		}
	}

	/**
	 * Adds an offender for a component whose declared event type at {@code index} of
	 * {@code declaringInterface} is unrelated to this context's {@code contextRootType}. The type
	 * arguments are named in full, since the mistake this catches is two contexts whose event roots
	 * often differ by package alone.
	 */
	private void checkEventType ( List<String> offenders, String kind, String name, Class<?> componentClass,
			Class<?> declaringInterface, int index, String role, Class<?> contextRootType ) {
		if ( contextRootType == null ) {
			return;
		}
		Class<?> declared = TypeArguments.of(componentClass, declaringInterface, index);
		if ( declared == null
				|| declared.isAssignableFrom(contextRootType)
				|| contextRootType.isAssignableFrom(declared) ) {
			return;
		}
		offenders.add("%s %s (its %s event type is %s, where this context's is %s)"
				.formatted(kind, name, role, declared.getName(), contextRootType.getName()));
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

		// A context configured with shredding hands its codec to the store: it is what seals Shreddable
		// values on append, unseals them on read, and holds the keys that erase() destroys. A codec
		// left unset is not "no codec": the store then takes the one the storage was built with
		// (EventStorage.shreddingCodec(), what a storage builder's .shredding(...) configures), and
		// only a storage carrying none gives an unprotected store. A codec given here wins over the
		// storage's, so a context can still narrow what it reads (a restricted or withholding codec)
		// on a storage whose codec holds every key.
		// The observer is treated the same way: left unset, the store reports to the storage's own.
		EventStore.Builder storeBuilder = EventStore.on(eventStorage);
		if ( eventStoreObserver != null ) {
			storeBuilder.observer(eventStoreObserver);
		}
		if ( shreddingCodec != null ) {
			storeBuilder.shredding(shreddingCodec);
		}
		EventStore eventStore = storeBuilder.build();

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

		EventSource<String> readAllInStoreEventStream;
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
		readAllInStoreEventStream = eventStore.getRawEventStream(EventStreamId.anyContext().anyPurpose());

		List<Slice<C>> deployedFeatureSlices = Collections.emptyList();
		List<Slice<C>> undeployedFeatureSlices = Collections.emptyList();

		if ( featuresSpecification.rootPackage() != null ) {
			deployedFeatureSlices =
			AnnotationBasedDiscoveryAndConfiguration.<Slice<C>>instantiateAndConfigure(
					FeatureSlice.class,
					featuresSpecification.rootPackage(),
					this::isSliceOfThisContext,
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
					this::isSliceOfThisContext,
					featuresSpecification.filter().negate(),
					fs->{});
		} else {
			LOGGER.warn("no features rootPackage");
		}

		// Every registration has now happened -- the ones made directly on the builder and the ones the
		// feature slices just made -- and no module exists yet. That is the one point at which these
		// checks see everything they are about and cost nothing but the reading: run before the scan
		// they would miss whatever a slice registers, which is how components are normally registered
		// at all, and run from inside a module each would fail on its own kind, so a deployment with
		// three mistakes would learn about them one build at a time.
		rejectComponentsOfAnotherContextsEventTypes();
		rejectReadModelsWithoutAChosenMode();
		rejectLiveReadModelsThatCannotBeInstantiated();
		rejectAutomationsWhoseTodoListIsNotProjectedHere();

		// contained once, here, so no module has to: whatever the observer throws never reaches the work
		BoundedContextObserver observer = BoundedContextObserver.contained(this.observer);

		BoundedContextEventEmitter eventEmitter = new BoundedContextEventEmitter(boundedContextListener, instance, new SliceRegistry(deployedFeatureSlices, sliceMembers), name, observer);

		// how each of these is projected (every instance or a single leader) follows from the read
		// model's own storage class, see ReadModelModule.createProjectorProcessors
		Collection<ReadModel> eventuallyConsistentReadModels = eventuallyConsistentReadModelSpecs.stream().map(EventuallyConsistentReadModelSpecificationImpl::readModel).collect(Collectors.toCollection(ArrayList::new));

		// each module is recorded as soon as it exists, so a failure in the next one still finds it --
		// see releasePartiallyBuilt
		Collection<Translator> translators = translatorSpecs.stream().collect(Collectors.toCollection(ArrayList::new));
		InboundModule im = new InboundModule(name, inboundEventStream, translators, instance, observer, eventEmitter);
		constructed.add(im);

		Collection<Dispatcher> dispatchers = dispatcherSpecs.stream().collect(Collectors.toCollection(ArrayList::new));
		OutboundModule om = new OutboundModule(name, outboundEventStream, dispatchers, instance, observer, eventEmitter);
		constructed.add(om);

		AutomationModule am = new AutomationModule(name, domainEventStream, automations, instance, observer, eventEmitter);
		constructed.add(am);

		ReadModelModule rmm = new ReadModelModule(name, domainEventStream, readAllInStoreEventStream, liveModelSpecs, eventuallyConsistentReadModels, instance, observer, eventEmitter);
		constructed.add(rmm);
		DCBModule dcb = new DCBModule(name, instance, rmm, domainEventStream, outboundEventStream, observer, eventEmitter);
		constructed.add(dcb);

		AggregateModule aggregateModule = new AggregateModule(name, instance, aggregateSpecifications, domainEventStream, observer, eventEmitter);

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
						domainEventStream, inboundEventStream, outboundEventStream, eventEmitter, dcb, aggregateModule, rmm, am, im, om, leaderElector, managementModule, instance, observer, adapterRegistry);

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
		private Class<? extends ReadModel<?>> readModelClass;
		private LiveModelSnapshotSpecificationImpl snapshotSpecification;
		// whether the caller has said how this read model is projected -- see rejectReadModelsWithoutAChosenMode
		private boolean modeChosen;

		public LiveModelSpecificationImpl ( BoundedContextBuilder<C> builder, Class<? extends ReadModel<?>> readModelClass ) {
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

		public Class<? extends ReadModel<?>> readModelClass ( ) {
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
		private ReadModel<?> readModel;
		// whether the caller has said how this read model is projected -- see rejectReadModelsWithoutAChosenMode
		private boolean modeChosen;

		public EventuallyConsistentReadModelSpecificationImpl ( BoundedContextBuilder<C> builder, ReadModel<?> readModel ) {
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

		public ReadModel<?> readModel ( ) {
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
