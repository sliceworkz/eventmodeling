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
package org.sliceworkz.eventmodeling.mock.wiring;

import java.util.Optional;

import org.sliceworkz.eventmodeling.automation.Policy;
import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.commands.Command;
import org.sliceworkz.eventmodeling.inbound.Translator;
import org.sliceworkz.eventmodeling.inbound.TranslatorContext;
import org.sliceworkz.eventmodeling.mock.boundedcontext.Mock;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockInboundEvent.SomeInboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent;
import org.sliceworkz.eventmodeling.mock.boundedcontext.MockOutboundEvent.SomeOutboundEvent;
import org.sliceworkz.eventmodeling.outbound.Dispatcher;
import org.sliceworkz.eventmodeling.outbound.Publisher;
import org.sliceworkz.eventmodeling.outbound.PublisherContext;
import org.sliceworkz.eventmodeling.readmodels.ReadModel;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.Slice;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * One member of every kind that declares a filter, each over known event types, plus a dispatcher filtering
 * on tags only and a read model matching nothing: what the slice inventory reports each member reads.
 */
@FeatureSlice(chapter = "Wiring")
public class WiringFeatureSlice implements Slice<Mock> {

	@Override
	public void configureCommand ( BoundedContextBuilder<Mock> builder ) {
		builder.inbound().translator(new WiringTranslator()).done();
	}

	@Override
	public void configureAutomation ( BoundedContextBuilder<Mock> builder ) {
		builder.outbound().publisher(new WiringPublisher()).dispatcher(new WiringDispatcher()).dispatcher(new TaggedDispatcher()).done();
		builder.policy(new WiringPolicy()).fromNowOn().stallOnRejection();
	}

	@Override
	public void configureProjection ( BoundedContextBuilder<Mock> builder ) {
		builder.readmodel(new WiringReadModel()).eventuallyConsistent();
		builder.readmodel(new NothingReadModel()).eventuallyConsistent();
	}

	public static class WiringTranslator implements Translator<MockInboundEvent, MockDomainEvent> {
		@Override public EventFilter eventFilter ( ) { return EventFilter.forTypes(SomeInboundEvent.class); }
		@Override public void translate ( Event<MockInboundEvent> event, TranslatorContext<MockInboundEvent, MockDomainEvent> context ) { }
	}

	public static class WiringPublisher implements Publisher<MockDomainEvent, MockOutboundEvent> {
		@Override public EventFilter eventFilter ( ) { return EventFilter.forTypes(FirstDomainEvent.class, SecondDomainEvent.class); }
		@Override public void publish ( Event<MockDomainEvent> event, PublisherContext<MockDomainEvent, MockOutboundEvent> context ) { }
	}

	public static class WiringDispatcher implements Dispatcher<MockOutboundEvent> {
		@Override public EventFilter eventFilter ( ) { return EventFilter.forTypes(SomeOutboundEvent.class); }
		@Override public void when ( Event<MockOutboundEvent> event ) { }
	}

	public static class TaggedDispatcher implements Dispatcher<MockOutboundEvent> {
		@Override public EventFilter eventFilter ( ) { return EventFilter.forTags("region", "EU"); }
		@Override public void when ( Event<MockOutboundEvent> event ) { }
	}

	/** Over the sealed root: it stands for every type under it. */
	public static class WiringPolicy implements Policy<MockDomainEvent> {
		@Override public EventFilter eventFilter ( ) { return EventFilter.forTypes(MockDomainEvent.class); }
		@Override public Optional<Command<MockDomainEvent>> react ( Event<MockDomainEvent> event ) { return Optional.empty(); }
	}

	public static class WiringReadModel implements ReadModel<MockDomainEvent> {
		@Override public EventFilter eventFilter ( ) { return EventFilter.forTypes(ThirdDomainEvent.class); }
		@Override public void when ( Event<MockDomainEvent> event ) { }
	}

	public static class NothingReadModel implements ReadModel<MockDomainEvent> {
		@Override public EventFilter eventFilter ( ) { return EventFilter.matchNone(); }
		@Override public void when ( Event<MockDomainEvent> event ) { }
	}

}
