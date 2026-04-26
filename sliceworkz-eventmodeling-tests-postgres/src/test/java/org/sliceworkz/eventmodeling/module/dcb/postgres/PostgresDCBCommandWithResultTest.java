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
package org.sliceworkz.eventmodeling.module.dcb.postgres;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.sliceworkz.eventmodeling.module.dcb.DCBCommandWithResultTest;
import org.sliceworkz.eventmodeling.testing.postgres.PostgresContainer;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.spi.EventStorage;

class PostgresDCBCommandWithResultTest {

	@Nested
	class OnPostgres17 extends DCBCommandWithResultTest {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG17); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG17); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17); }

		@Override
		public EventStorage createEventStorage ( ) {
			return PostgresEventStorage.newBuilder().name("unit-test").dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG17)).initializeDatabase().build();
		}

		@Override
		public void destroyEventStorage ( EventStorage storage ) {
			((PostgresEventStorageImpl)storage).stop();
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG17);
		}
	}

	@Nested
	class OnPostgres18 extends DCBCommandWithResultTest {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

		@Override
		public EventStorage createEventStorage ( ) {
			return PostgresEventStorage.newBuilder().name("unit-test").dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18)).initializeDatabase().build();
		}

		@Override
		public void destroyEventStorage ( EventStorage storage ) {
			((PostgresEventStorageImpl)storage).stop();
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}

}
