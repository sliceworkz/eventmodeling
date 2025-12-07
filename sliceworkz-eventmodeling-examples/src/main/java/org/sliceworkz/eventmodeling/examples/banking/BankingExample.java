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
package org.sliceworkz.eventmodeling.examples.banking;

import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.domain.DomainConceptId;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent.AccountOpened;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.features.accountdetails.AccountDetailsReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.accountoverview.AccountOverviewReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.openaccount.OpenAccountCommand;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class BankingExample {
	
	public static void main ( String[] args ) {
		
		EventStorage eventStorage = InMemoryEventStorage.newBuilder().build();
		EventStore eventStore = EventStoreFactory.get().eventStore(eventStorage);

		Instance instance = InstanceFactory.determine("banking-app");
		
		/*
		 * Create the BoundedContext 
		 */
		BankingBoundedContext bc = BoundedContext.newBuilder(BankingDomainEvent.class,BankingInboundEvent.class,BankingOutboundEvent.class)
			.name("banking")
			.eventStorage(eventStorage)
			.instance(instance)
			.rootPackage(BankingExample.class.getPackage())
			.build(BankingBoundedContext.class);
		
		EventStream<BankingDomainEvent> eventStream = eventStore.getEventStream(EventStreamId.forContext("banking").withPurpose("domain"), BankingDomainEvent.class);
		
		/*
		 * At this point, only an Obserability event (BoundedContextStarted) will be present 
		 */
		eventStore.getEventStream(EventStreamId.anyContext()).query(EventQuery.matchAll()).forEach(System.out::println);
		
		/*
		 * Register a Subscriber on all event updates that justs prints out what has been added to the eventlog 
		 */
		eventStream.subscribe(
				new EventStreamEventuallyConsistentAppendListener() {
					
					private EventReference lastSeen;
					
					@Override
					public EventReference eventsAppended(EventReference atLeastUntil) {
						List<Event<BankingDomainEvent>> events = eventStream.query(EventQuery.matchAll(), lastSeen).toList();
						events.forEach(System.out::println);
						if ( events.size() > 0 ) {
							lastSeen = events.getLast().reference();
						}
						return lastSeen;
					}
				}
			);

		
		// Open an Account
		Optional<EventReference> ref = bc.execute(new OpenAccountCommand(DomainConceptId.create()));
		
		// Go fetch the AccountOpened Event that should have been raised by the OpenAccountCommmand
		AccountOpened ao = eventStream.getEventById(ref.get().id()).map(Event::data).map(e->(AccountOpened)e).get();
		
		// Render a live model with the details of the Account
		AccountDetailsReadModel rm = bc.read(AccountDetailsReadModel.class, ao.accountId());
		System.out.println(rm.getAccountDetails());
		
		// Also print out the eventually consistent readmodel on all accounts
		System.out.println(AccountOverviewReadModel.INSTANCE.getAccounts());
		
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
	}
}
