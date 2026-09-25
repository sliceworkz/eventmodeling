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
package org.sliceworkz.eventmodeling.examples.banking;

import java.util.Set;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContext;
import org.sliceworkz.eventmodeling.boundedcontext.LoggingBoundedContextListener;
import org.sliceworkz.eventmodeling.commands.CommandExecutionResult;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventmodeling.events.InstanceFactory;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.AccountId;
import org.sliceworkz.eventmodeling.examples.banking.features.accountdetails.AccountDetailsReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.accountoverview.AccountOverviewReadModel;
import org.sliceworkz.eventmodeling.examples.banking.features.accountoverview.AccountOverviewReadModel.AccountSummary;
import org.sliceworkz.eventmodeling.examples.banking.features.openaccount.OpenAccountCommand;
import org.sliceworkz.eventmodeling.examples.banking.features.openaccountwithresult.OpenAccountWithResultCommand;
import org.sliceworkz.eventmodeling.readmodels.ReadModelResult;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;

public class BankingExample {
	
	public static void main ( String[] args ) {
		
		EventStorage eventStorage = InMemoryEventStorage.newBuilder().build();

		Instance instance = InstanceFactory.determine("banking-app");
		
		/*
		 * Create the BoundedContext. The LoggingBoundedContextListener logs everything the context
		 * reports -- among it a CommandExecuted per command, naming the events that command raised --
		 * which is the view of "what was appended" an application needs. Pass a
		 * StreamAppendingBoundedContextListener instead to keep that record in a stream of your own.
		 */
		Banking bc = BoundedContext.newBuilder(Banking.class)
			.name("banking")
			.eventStorage(eventStorage)
			.instance(instance)
			.listener(new LoggingBoundedContextListener())
			.features()
				.rootPackage(BankingExample.class.getPackage())
				.done()
			.build();

		bc.start();

		/*
		 * Everything from here on is application code: it decides and it reads, and it has no business
		 * erasing a person, stopping an automation or terminating the context -- all of which a
		 * Banking reference can do, because whatever built the context needs to. So hand that code the
		 * narrower surface instead. It costs this one line: a built context already is a BankingApi,
		 * and app.terminate() now does not compile.
		 *
		 * Note what the application does not do: open the event stream. It decides through commands and
		 * reads through read models, and those are the only two ways in it needs. See WHO-MAY-DO-WHAT.md
		 * for the other audiences.
		 */
		BankingApi app = bc;

		/*
		 * Open an account with a plain Command. It answers with the reference of the event it appended
		 * -- not with the new account's id, which the command minted for itself and did not hand back.
		 */
		EventReference opened = app.execute(new OpenAccountCommand(BankingDomain.CUSTOMER.newId())).orElseThrow();

		/*
		 * The overview of all accounts is an eventually consistent read model: a background thread
		 * projects it, so the account opened a moment ago may not be in it yet. published() hands back
		 * its state together with the position that state reflects, as one observation -- so the
		 * reference the command returned is all a caller needs to tell whether its own write is in the
		 * answer, rather than guessing. An empty position means nothing has reached it yet at all.
		 */
		ReadModelResult<Set<AccountSummary>> overview = AccountOverviewReadModel.INSTANCE.published();
		boolean includesOurAccount = overview.upTo() != null && !opened.happenedAfter(overview.upTo());
		System.out.println(overview.data());
		System.out.println("  (as projected up to " + overview.upTo() + "; includes the account just opened: " + includesOurAccount + ")");

		/*
		 * When the caller needs something the command decided -- here the id, to read the account
		 * straight back -- the command says so in its type: a CommandWithResult returns it. The live
		 * read model below is projected on the spot, so unlike the overview it is always current.
		 */
		CommandExecutionResult<AccountId> openResult =
				app.execute(new OpenAccountWithResultCommand(BankingDomain.CUSTOMER.newId()));

		AccountId accountId = openResult.response();
		System.out.println("Account opened with ID: " + accountId);

		AccountDetailsReadModel details = app.read(AccountDetailsReadModel.class, accountId);
		System.out.println(details.getAccountDetails());

		/*
		 * Shut down from the outside in: terminate the bounded context (which closes the EventStore it
		 * built for itself), then the storage behind it. This process would exit cleanly without either
		 * -- a shutdown hook terminates the context -- but an application that keeps running after its
		 * bounded context is done has to release both, and the storage is never released for it.
		 */
		bc.terminate();
		eventStorage.close();
	}
}
