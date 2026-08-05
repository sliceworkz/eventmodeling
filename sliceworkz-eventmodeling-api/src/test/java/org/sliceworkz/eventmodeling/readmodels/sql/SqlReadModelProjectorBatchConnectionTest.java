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
package org.sliceworkz.eventmodeling.readmodels.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * A SQL read model hands its pooled connection back at the end of every batch, including the batches
 * that go wrong. A projection that cannot commit is precisely the one that keeps trying, so a
 * connection kept back on that path drains the pool.
 */
class SqlReadModelProjectorBatchConnectionTest {

	@Test
	void aConnectionIsReturnedAfterAnOrdinaryBatch ( ) {
		CountingDataSource dataSource = new CountingDataSource(false, false);
		TestProjector projector = new TestProjector(dataSource);

		projector.beforeBatch();
		projector.afterBatch(Optional.empty());

		assertEquals(1, dataSource.handedOut(), "one connection per batch");
		assertEquals(1, dataSource.closed(), "and it goes back at the end of it");
	}

	@Test
	void aConnectionIsReturnedAfterACommitThatFails ( ) {
		CountingDataSource dataSource = new CountingDataSource(true, false);
		TestProjector projector = new TestProjector(dataSource);

		projector.beforeBatch();
		assertThrows(RuntimeException.class, () -> projector.afterBatch(Optional.empty()));

		assertEquals(1, dataSource.closed(), "the connection goes back even though the commit failed");
	}

	@Test
	void aConnectionIsReturnedAfterARollbackThatFails ( ) {
		CountingDataSource dataSource = new CountingDataSource(false, true);
		TestProjector projector = new TestProjector(dataSource);

		projector.beforeBatch();
		assertThrows(RuntimeException.class, projector::cancelBatch);

		assertEquals(1, dataSource.closed(), "the connection goes back even though the rollback failed");
	}

	/** And repeated failures do not accumulate: every connection handed out still comes back. */
	@Test
	void batchesThatKeepFailingToCommitDoNotAccumulateConnections ( ) {
		CountingDataSource dataSource = new CountingDataSource(true, false);
		TestProjector projector = new TestProjector(dataSource);

		for ( int batch = 0; batch < 10; batch++ ) {
			projector.beforeBatch();
			assertThrows(RuntimeException.class, () -> projector.afterBatch(Optional.empty()));
		}

		assertEquals(10, dataSource.handedOut());
		assertEquals(10, dataSource.closed(), "every connection handed out was handed back");
	}

	/** A connection taken but never usable is not left to the garbage collector either. */
	@Test
	void aConnectionIsReturnedWhenTheBatchCannotEvenBeStarted ( ) {
		CountingDataSource dataSource = new CountingDataSource(false, false);
		dataSource.failSetAutoCommit();
		TestProjector projector = new TestProjector(dataSource);

		assertThrows(RuntimeException.class, projector::beforeBatch);

		assertEquals(1, dataSource.handedOut());
		assertEquals(1, dataSource.closed());
	}

	private static class TestProjector extends SqlReadModelProjector<Object> {

		TestProjector ( DataSource dataSource ) {
			super(dataSource, "rm_test");
		}

		@Override
		protected String[] createTables ( ) {
			return new String[0];
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		protected void project ( Event<Object> event ) {
		}
	}

	/**
	 * A DataSource handing out connections that do nothing but count their own close, and fail on
	 * demand where a real one would.
	 */
	private static class CountingDataSource implements DataSource {

		private final boolean commitFails;
		private final boolean rollbackFails;
		private boolean setAutoCommitFails;
		private final List<Connection> handedOut = new ArrayList<>();
		private int closed;

		CountingDataSource ( boolean commitFails, boolean rollbackFails ) {
			this.commitFails = commitFails;
			this.rollbackFails = rollbackFails;
		}

		void failSetAutoCommit ( ) {
			this.setAutoCommitFails = true;
		}

		/**
		 * Named so that constructing the projector can work out its storage class from here rather than
		 * by opening a connection, which would otherwise show up in these counts.
		 */
		public String getJdbcUrl ( ) {
			return "jdbc:h2:mem:readmodels";
		}

		int handedOut ( ) {
			return handedOut.size();
		}

		int closed ( ) {
			return closed;
		}

		@Override
		public Connection getConnection ( ) {
			Connection connection = (Connection) Proxy.newProxyInstance(
					getClass().getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> {
						switch ( method.getName() ) {
							case "close" -> closed++;
							case "commit" -> failIf(commitFails, "commit refused");
							case "rollback" -> failIf(rollbackFails, "rollback refused");
							case "setAutoCommit" -> failIf(setAutoCommitFails, "setAutoCommit refused");
							default -> { }
						}
						return null;
					});
			handedOut.add(connection);
			return connection;
		}

		private static void failIf ( boolean fails, String message ) throws SQLException {
			if ( fails ) {
				throw new SQLException(message);
			}
		}

		@Override
		public Connection getConnection ( String username, String password ) {
			return getConnection();
		}

		@Override
		public PrintWriter getLogWriter ( ) {
			return null;
		}

		@Override
		public void setLogWriter ( PrintWriter out ) {
		}

		@Override
		public void setLoginTimeout ( int seconds ) {
		}

		@Override
		public int getLoginTimeout ( ) {
			return 0;
		}

		@Override
		public Logger getParentLogger ( ) throws SQLFeatureNotSupportedException {
			throw new SQLFeatureNotSupportedException();
		}

		@Override
		public <T> T unwrap ( Class<T> iface ) throws SQLException {
			throw new SQLException("not a wrapper");
		}

		@Override
		public boolean isWrapperFor ( Class<?> iface ) {
			return false;
		}
	}

}
