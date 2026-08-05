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

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.readmodels.ReadModelStorage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * How a SQL read model works out where its state lives, which decides whether it is projected on every
 * instance or on a single leader, and whether its bookmark is dropped at startup.
 * <p>
 * There is no accessor for a JDBC URL on {@link DataSource}, so it has to be asked for by a name the
 * implementation happens to use, and the pools disagree about that name.
 * <p>
 * The fakes here stand in for the pools rather than depending on them, so the test says what it is about
 * and needs no database.
 */
class SqlReadModelStorageDetectionTest {

	@Test
	void anInMemoryH2BehindAPoolThatNamesItsUrlGetterGetJdbcUrlIsEphemeral ( ) {
		// HikariCP, c3p0
		assertEquals(ReadModelStorage.EPHEMERAL, storageOf(new GetJdbcUrlDataSource("jdbc:h2:mem:readmodels")));
	}

	@Test
	void anInMemoryH2BehindADataSourceThatNamesItGetUrlIsEphemeral ( ) {
		// H2's own JdbcDataSource, PGSimpleDataSource
		assertEquals(ReadModelStorage.EPHEMERAL, storageOf(new GetUppercaseUrlDataSource("jdbc:h2:mem:readmodels")));
	}

	@Test
	void anInMemoryH2BehindADataSourceThatNamesItGetUrlLowercaseIsEphemeral ( ) {
		// Commons DBCP, Tomcat JDBC
		assertEquals(ReadModelStorage.EPHEMERAL, storageOf(new GetLowercaseUrlDataSource("jdbc:h2:mem:readmodels")));
	}

	@Test
	void aDurableDatabaseIsShared ( ) {
		assertEquals(ReadModelStorage.SHARED, storageOf(new GetJdbcUrlDataSource("jdbc:postgresql://db:5432/readmodels")));
		assertEquals(ReadModelStorage.SHARED, storageOf(new GetJdbcUrlDataSource("jdbc:h2:file:./data/readmodels")));
	}

	/**
	 * A DataSource exposing no URL getter at all is asked through a connection, which is authoritative
	 * and sees through any wrapper.
	 */
	@Test
	void aDataSourceExposingNoUrlGetterIsAskedThroughAConnection ( ) {
		assertEquals(ReadModelStorage.EPHEMERAL, storageOf(new OpaqueDataSource("jdbc:h2:mem:readmodels")));
	}

	/**
	 * And when even that fails there is nothing left to go on. SHARED is the deliberate answer: a shared
	 * read model mistaken for an ephemeral one reprojects its whole history into a durable database on
	 * every start, which the other way round does not.
	 */
	@Test
	void aDataSourceThatCannotBeAskedAtAllIsAssumedShared ( ) {
		assertEquals(ReadModelStorage.SHARED, storageOf(new OpaqueDataSource(null)));
	}

	private static ReadModelStorage storageOf ( DataSource dataSource ) {
		return new TestProjector(dataSource).storage();
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

	/** A DataSource that can answer nothing but {@code getConnection()}, and only when given a URL. */
	private static class OpaqueDataSource implements DataSource {

		private final String url;

		OpaqueDataSource ( String url ) {
			this.url = url;
		}

		/**
		 * A connection that can do nothing but report its URL through its metadata — enough for the
		 * detection, and no driver or database needed to say so.
		 */
		@Override
		public Connection getConnection ( ) throws SQLException {
			if ( url == null ) {
				throw new SQLException("no database here");
			}
			DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
					getClass().getClassLoader(), new Class<?>[] { DatabaseMetaData.class },
					(proxy, method, args) -> "getURL".equals(method.getName()) ? url : null);
			return (Connection) Proxy.newProxyInstance(
					getClass().getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> "getMetaData".equals(method.getName()) ? metaData : null);
		}

		@Override
		public Connection getConnection ( String username, String password ) throws SQLException {
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

	private static class GetJdbcUrlDataSource extends OpaqueDataSource {
		private final String url;
		GetJdbcUrlDataSource ( String url ) { super(null); this.url = url; }
		@SuppressWarnings("unused") public String getJdbcUrl ( ) { return url; }
	}

	private static class GetUppercaseUrlDataSource extends OpaqueDataSource {
		private final String url;
		GetUppercaseUrlDataSource ( String url ) { super(null); this.url = url; }
		@SuppressWarnings("unused") public String getURL ( ) { return url; }
	}

	private static class GetLowercaseUrlDataSource extends OpaqueDataSource {
		private final String url;
		GetLowercaseUrlDataSource ( String url ) { super(null); this.url = url; }
		@SuppressWarnings("unused") public String getUrl ( ) { return url; }
	}

}
