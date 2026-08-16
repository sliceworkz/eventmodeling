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
package org.sliceworkz.eventmodeling.boundedcontext;

import java.time.Duration;

import org.sliceworkz.eventmodeling.EventTypes; // retained for javadoc reference
import org.sliceworkz.eventmodeling.aggregates.Aggregate;
import org.sliceworkz.eventmodeling.aggregates.AggregateSpecification;
import org.sliceworkz.eventmodeling.automation.Automation;
import org.sliceworkz.eventmodeling.commands.AbstractCommand;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.commands.CommandWithResult;
import org.sliceworkz.eventmodeling.commands.OutboundCommand;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.readmodels.EventuallyConsistentReadModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.LiveModelSpecification;
import org.sliceworkz.eventmodeling.readmodels.ReadModelWithMetaData;
import org.sliceworkz.eventstore.MeterOptions;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
import org.sliceworkz.eventstore.spi.EventStorage;

import io.micrometer.core.instrument.MeterRegistry;

public interface BoundedContextBuilder<C extends BoundedContext<?,?,?>> {

	BoundedContextBuilder<C> name(String name);

	BoundedContextBuilder<C> contextType(Class<C> contextType);

	BoundedContextBuilder<C> eventTypes(
			Class<?> domainEventRootType,
			Class<?> inboundEventRootType,
			Class<?> outboundEventRootType);

	BoundedContextBuilder<C> historicalEventTypes(
			Class<?> historicalDomainEventRootType,
			Class<?> historicalInboundEventRootType,
			Class<?> historicalOutboundEventRootType);

	BoundedContextBuilder<C> instance(Instance instance);

	BoundedContextBuilder<C> meterRegistry(MeterRegistry meterRegistry);

	/**
	 * How the event store tags the meters it registers, in particular how far it breaks them down by
	 * stream purpose.
	 * <p>
	 * The default caps the {@code purpose} tag at 1000 distinct values and pools everything past that
	 * under {@code _other}, which is the right answer for almost every context. Set this where you know
	 * your own cardinality better than that default can:
	 * <pre>{@code
	 * // purpose is an entity id here -- never break down by it
	 * .meterOptions(MeterOptions.withoutPurposeBreakdown())
	 *
	 * // a wider, but genuinely bounded, set of purposes
	 * .meterOptions(MeterOptions.withMaxPurposeTagValues(5000))
	 * }</pre>
	 * Nothing evicts a meter once it is registered, so an uncapped high-cardinality purpose grows the
	 * process for as long as it runs, with nothing failing to say so. A Micrometer {@code MeterFilter}
	 * is not a substitute: it runs at registration, while the store keys some of its own state on the
	 * tags it asked for.
	 *
	 * @param meterOptions how to tag the store's meters; null restores the defaults
	 * @return this builder
	 */
	BoundedContextBuilder<C> meterOptions(MeterOptions meterOptions);

	/**
	 * The storage the bounded context keeps its events in.
	 * <p>
	 * The storage stays yours: the context builds its own {@code EventStore} over it and closes only
	 * that one on {@link LifecycleCapability#terminate() terminate()}. Closing the storage — and
	 * anything you supplied it, such as a DataSource, in that order — is the caller's business, once
	 * every context on it has been terminated. One storage can back several bounded contexts.
	 */
	BoundedContextBuilder<C> eventStorage(EventStorage eventStorage);

	/**
	 * Protects the {@link org.sliceworkz.eventstore.shredding.Shreddable} values in this context's
	 * events, keeping the keys in the given key store.
	 * <p>
	 * This is the whole setup. The shipped AES-256-GCM codec is applied for you, so nothing here needs
	 * to know about ciphers, initialisation vectors or envelopes — pick where the keys live and the rest
	 * follows:
	 * <pre>{@code
	 * // production: keys in the same PostgreSQL database as the events, so a minted key and the
	 * // append that seals under it commit together
	 * .shredding(PostgresShreddingKeyStore.on(dataSource, "acme_"))
	 *
	 * // a file-backed store, beside file-backed events
	 * .shredding(new InMemoryFsShreddingKeyStore(directory))
	 *
	 * // development and tests only -- keys die with the JVM, so every event sealed by a previous run
	 * // reads as erased
	 * .shredding(new InMemoryShreddingKeyStore())
	 * }</pre>
	 * Without this, an event type declaring a {@code Shreddable} component cannot be registered at all:
	 * the context fails at startup rather than storing personal data in the clear with no key to destroy.
	 * <p>
	 * The key store stays yours, exactly as {@link #eventStorage(EventStorage)} does — the context never
	 * closes it. Where the key store shares the storage's {@code DataSource}, closing the storage is
	 * enough.
	 *
	 * @param shreddingKeyStore where keys are minted, resolved and destroyed
	 * @return this builder
	 * @see PrivacyCapability#erase(org.sliceworkz.eventstore.shredding.DataSubject, org.sliceworkz.eventstore.shredding.ErasureReason)
	 */
	BoundedContextBuilder<C> shredding(ShreddingKeyStore shreddingKeyStore);

	/**
	 * Protects personal data with a codec of your own, taking over encryption as well as key storage.
	 * <p>
	 * The seam for a codec that keeps key material inside an HSM, so it never enters this JVM. Prefer
	 * {@link #shredding(ShreddingKeyStore)} unless you need that: it applies the shipped, tested codec
	 * and leaves you only the question of where keys live.
	 *
	 * @param shreddingCodec seals and unseals protected values
	 * @return this builder
	 */
	BoundedContextBuilder<C> shredding(ShreddingCodec shreddingCodec);

	/**
	 * This deployment's priority in leader election, default {@code 0}.
	 * <p>
	 * Leader-only processors — automations, SHARED read models' projectors, translators and
	 * dispatchers — run on the single instance holding their lease. When a live contender with a
	 * <b>strictly higher</b> priority appears, the current leader finishes its batch and hands the
	 * lease over, so the preferred instance regains leadership when it comes back. Equal priorities
	 * never preempt: whoever holds a lease keeps it, which is what keeps a symmetric deployment
	 * stable.
	 *
	 * @param priority higher wins leadership back; equal never preempts
	 * @return this builder
	 */
	BoundedContextBuilder<C> leadershipPriority ( long priority );

	/**
	 * The pacing of leader election, defaults 5 seconds heartbeat and 20 seconds time-to-live.
	 * <p>
	 * The elected leader renews its leases every {@code heartbeat}, off the processing path — no
	 * batch or projection ever waits for a renewal. A lease not renewed within {@code ttl} (judged
	 * on the event storage's clock) is expired and taken over, so {@code ttl} bounds how long
	 * processing pauses when an instance dies without releasing; a graceful stop or step-down hands
	 * over within a heartbeat or two. An instance that cannot <em>confirm</em> a renewal demotes
	 * itself before the ttl elapses, which is what keeps two leaders from overlapping.
	 *
	 * @param heartbeat how often leases are renewed; must be positive
	 * @param ttl how long an unrenewed lease survives; must be at least twice the heartbeat, so a
	 *        single failed renewal does not cost leadership
	 * @return this builder
	 */
	BoundedContextBuilder<C> leadershipIntervals ( Duration heartbeat, Duration ttl );

	FeaturesSpecification<C> features ( );

	/**
	 * Registers a listener notified of {@link BoundedContextEvent}s produced by the kernel of this
	 * bounded context (lifecycle, commands executed, read models updated, ...).
	 * <p>
	 * When no listener is registered no kernel events are produced and there is no overhead. Two
	 * ready-to-use implementations are provided: {@link StreamAppendingBoundedContextListener} and
	 * {@link LoggingBoundedContextListener}.
	 *
	 * @param listener the listener to notify; must not be {@code null}
	 * @return this builder
	 */
	BoundedContextBuilder<C> listener ( BoundedContextListener listener );

	/**
	 * Declares the commands a feature slice contains, so that the slice reports them from the moment
	 * the bounded context starts instead of only after each has been executed once.
	 * <p>
	 * Purely declarative. Unlike every other registration on this builder, a command is not wired into
	 * anything: it is instantiated by the caller and executed ad hoc, and attributed to its slice by
	 * package convention whether or not it is declared here. Declaring it only fills in
	 * {@link BoundedContextEvent.FeatureSlice#members()}, which is what an observer (a monitoring
	 * dashboard) reads to show a slice's contents before it has done any work.
	 * <p>
	 * Call it from {@link org.sliceworkz.eventmodeling.slices.Slice#configureCommand}, so the commands
	 * are attributed to that slice and are only declared where commands are actually deployed:
	 * <pre>{@code
	 * @FeatureSlice(type = Type.STATE_CHANGE)
	 * public class PlaceOrderFeatureSlice implements Slice<Orders> {
	 *     @Override
	 *     public void configureCommand ( BoundedContextBuilder<Orders> builder ) {
	 *         builder.command(PlaceOrderCommand.class, CancelOrderCommand.class);
	 *     }
	 * }
	 * }</pre>
	 * A command declared outside a slice's configuration belongs to no slice and is ignored.
	 *
	 * @param commandClasses the command classes; each must implement {@link AbstractCommand}
	 *                       (so {@link Command} or {@link OutboundCommand}) or {@link CommandWithResult}
	 * @return this builder
	 * @throws IllegalArgumentException if a class is {@code null} or is not a command
	 */
	BoundedContextBuilder<C> command(Class<?>... commandClasses);

	AggregateSpecification<C> aggregate(Class<? extends Aggregate<?>> aggregateClass);

	LiveModelSpecification<C> readmodel(Class<? extends ReadModelWithMetaData<?>> readModelClass);

	EventuallyConsistentReadModelSpecification<C> readmodel(ReadModelWithMetaData<?> readModel);

	BoundedContextBuilder<C> automation(Automation<?,?,?> automation);

	BoundedContextBuilder<C> translator(Translator<?,?> translator);

	BoundedContextBuilder<C> translator(Class<? extends Translator<?,?>> translatorClass);

	BoundedContextBuilder<C> dispatcher(Dispatcher<?> dispatcher);

	BoundedContextBuilder<C> dispatcher(Class<? extends Dispatcher<?>> dispatcherClass);

	/**
	 * Starts an adapter binding for the given adapter instance.
	 * <p>
	 * The returned {@link AdapterBinding} must be completed by calling
	 * {@link AdapterBinding#forPort(Class)} to specify the port type.
	 * <p>
	 * Example:
	 * <pre>
	 *   .adapter(myDataSource).forPort(DataSource.class)
	 *   .adapter(myCache).forPort(Cache.class, "customers")
	 * </pre>
	 *
	 * @param adapter the adapter instance implementing a port
	 * @return an adapter binding to specify the port type
	 */
	AdapterBinding<C> adapter(Object adapter);

	/**
	 * Retrieves the adapter registered for the given port type with the default qualification.
	 *
	 * @param <T> the port type
	 * @param portType the port interface class
	 * @return the adapter instance, cast to the port type
	 * @throws IllegalStateException if no adapter is registered for this port
	 */
	<T> T port(Class<T> portType);

	/**
	 * Retrieves the adapter registered for the given port type and qualification.
	 *
	 * @param <T> the port type
	 * @param portType the port interface class
	 * @param qualification the qualification name
	 * @return the adapter instance, cast to the port type
	 * @throws IllegalStateException if no adapter is registered for this port and qualification
	 */
	<T> T port(Class<T> portType, String qualification);

	C build();

}
