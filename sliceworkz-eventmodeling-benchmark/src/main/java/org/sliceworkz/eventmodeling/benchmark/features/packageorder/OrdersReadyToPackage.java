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
package org.sliceworkz.eventmodeling.benchmark.features.packageorder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.sliceworkz.eventmodeling.automation.TodoListReadModel;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent.OrderPackaged;
import org.sliceworkz.eventmodeling.benchmark.OrderProcessingEvent.OrderProcessingDomainEvent.OrderReceived;
import org.sliceworkz.eventmodeling.benchmark.features.packageorder.OrdersReadyToPackage.OrderReadyToPackage;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;

public class OrdersReadyToPackage implements TodoListReadModel<OrderProcessingDomainEvent,OrderReadyToPackage>, BatchAwareProjection<OrderProcessingDomainEvent> {

	private DataSource dataSource;
	private Connection connection;
	private EventReference lastEventReference;

	public OrdersReadyToPackage ( DataSource dataSource ) {
		this.dataSource = dataSource;
	}

	public void initialize() {
		try (var connection = dataSource.getConnection();
			 var statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS todo_ready_to_package");
			statement.execute("""
				CREATE TABLE todo_ready_to_package (
					order_id BIGINT PRIMARY KEY
				)
				""");
		} catch (SQLException e) {
			throw new RuntimeException("Failed to initialize todo_ready_to_package table", e);
		}
	}

	@Override
	public EventQuery eventQuery() {
		return EventQuery.forEvents(EventTypesFilter.of(OrderReceived.class, OrderPackaged.class),Tags.none());
	}

	@Override
	public synchronized void when(Event<OrderProcessingDomainEvent> eventWithMeta) {
		try {
			switch(eventWithMeta.data()) {
				case OrderProcessingDomainEvent.OrderReceived e -> {
					try (var stmt = connection.prepareStatement(
						"INSERT INTO todo_ready_to_package (order_id) VALUES (?) ON CONFLICT DO NOTHING")) {
						stmt.setLong(1, e.orderId());
						stmt.executeUpdate();
					}
				}
				case OrderProcessingDomainEvent.OrderPackaged e -> {
					try (var stmt = connection.prepareStatement(
						"DELETE FROM todo_ready_to_package WHERE order_id = ?")) {
						stmt.setLong(1, e.orderId());
						stmt.executeUpdate();
					}
				}
				default -> { }
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to update todo_ready_to_package", e);
		}
		lastEventReference = eventWithMeta.reference();
	}

	@Override
	public Optional<EventReference> lastEventReference() {
		return Optional.ofNullable(lastEventReference);
	}

	@Override
	public synchronized Stream<OrderReadyToPackage> streamItems(Limit limit) {
		try (var connection = dataSource.getConnection()) {
			String sql = limit.isSet()
				? "SELECT order_id FROM todo_ready_to_package LIMIT ?"
				: "SELECT order_id FROM todo_ready_to_package";

			PreparedStatement stmt = connection.prepareStatement(sql);
			if (limit.isSet()) {
				stmt.setLong(1, limit.value());
			}

			try (stmt; var rs = stmt.executeQuery()) {
				List<OrderReadyToPackage> results = new ArrayList<>();
				while (rs.next()) {
					results.add(new OrderReadyToPackage(rs.getLong("order_id")));
				}
				return results.stream();
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to query todo_ready_to_package", e);
		}
	}

	@Override
	public void beforeBatch() {
		try {
			connection = dataSource.getConnection();
			connection.setAutoCommit(false);
		} catch (SQLException e) {
			throw new RuntimeException("Failed to start transaction", e);
		}
	}

	@Override
	public void afterBatch(Optional<EventReference> arg0) {
		try {
			if (connection != null) {
				connection.commit();
				connection.close();
				connection = null;
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to commit transaction", e);
		}
	}

	@Override
	public void cancelBatch() {
		try {
			if (connection != null) {
				connection.rollback();
				connection.close();
				connection = null;
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to rollback transaction", e);
		}
	}

	public record OrderReadyToPackage ( long orderId ) { }

}
