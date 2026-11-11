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
package org.sliceworkz.eventmodeling.examples.localfirst;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sliceworkz.eventmodeling.examples.localfirst.crdt.ReplicaId;

/**
 * A simulated network between replicas: which links exist (the topology), which are up, and an
 * anti-entropy exchange over a link. No sockets, no threads: a test decides when replicas talk.
 *
 * <p>A peer-to-peer network links every replica to every other one; a star links every replica to a
 * central hub only. The hub is an ordinary replica: it decides nothing for the others, it only relays.
 * In both, convergence takes nothing but the operations and their vector clocks.
 */
public final class Network {

	private record Link ( ReplicaId a, ReplicaId b ) {
		static Link between ( ReplicaId x, ReplicaId y ) {
			return x.value().compareTo(y.value()) <= 0 ? new Link(x, y) : new Link(y, x);
		}
		boolean touches ( ReplicaId replica ) {
			return a.equals(replica) || b.equals(replica);
		}
	}

	private final Map<ReplicaId, TodoListServer> replicas = new LinkedHashMap<>();
	private final Set<Link> links = new LinkedHashSet<>();
	private final Set<Link> down = new HashSet<>();
	private int operationsSent;

	private Network ( List<TodoListServer> servers ) {
		servers.forEach(s -> replicas.put(s.replica(), s));
	}

	/** Every replica linked to every other one. */
	public static Network peerToPeer ( TodoListServer... servers ) {
		Network network = new Network(Arrays.asList(servers));
		for ( int i = 0; i < servers.length; i++ ) {
			for ( int j = i + 1; j < servers.length; j++ ) {
				network.links.add(Link.between(servers[i].replica(), servers[j].replica()));
			}
		}
		return network;
	}

	/** Every client linked to the hub, and to nothing else. */
	public static Network star ( TodoListServer hub, TodoListServer... clients ) {
		Network network = new Network(concat(hub, clients));
		for ( TodoListServer client : clients ) {
			network.links.add(Link.between(hub.replica(), client.replica()));
		}
		return network;
	}

	public void disconnect ( TodoListServer x, TodoListServer y ) {
		down.add(existing(x, y));
	}

	public void reconnect ( TodoListServer x, TodoListServer y ) {
		down.remove(existing(x, y));
	}

	/** Takes every link of a replica down: an office that lost its connection. */
	public void isolate ( TodoListServer server ) {
		links.stream().filter(l -> l.touches(server.replica())).forEach(down::add);
	}

	public void rejoin ( TodoListServer server ) {
		down.removeIf(l -> l.touches(server.replica()));
	}

	public void healAll ( ) {
		down.clear();
	}

	public boolean connected ( TodoListServer x, TodoListServer y ) {
		Link link = Link.between(x.replica(), y.replica());
		return links.contains(link) && !down.contains(link);
	}

	/**
	 * One anti-entropy exchange over a link that is up: each side sends what the other's clock says it
	 * lacks. Answers how many operations were delivered; nothing when the link is down.
	 */
	public int sync ( TodoListServer x, TodoListServer y ) {
		if ( !connected(x, y) ) {
			return 0;
		}
		var toY = x.operationsMissingAt(y.versionVector());
		var toX = y.operationsMissingAt(x.versionVector());
		operationsSent += toY.size() + toX.size();
		return y.receive(toY) + x.receive(toX);
	}

	/** Gossips over every link that is up until nothing moves any more. Answers the number of rounds. */
	public int synchronize ( ) {
		int rounds = 0;
		int delivered;
		do {
			delivered = 0;
			for ( Link link : links ) {
				delivered += sync(replicas.get(link.a()), replicas.get(link.b()));
			}
			rounds++;
		} while ( delivered > 0 );
		return rounds;
	}

	/** How many operations were sent over the network so far, duplicates of anything included. */
	public int operationsSent ( ) {
		return operationsSent;
	}

	private Link existing ( TodoListServer x, TodoListServer y ) {
		Link link = Link.between(x.replica(), y.replica());
		if ( !links.contains(link) ) {
			throw new IllegalArgumentException("there is no link between " + x.replica() + " and " + y.replica());
		}
		return link;
	}

	private static List<TodoListServer> concat ( TodoListServer first, TodoListServer... rest ) {
		TodoListServer[] all = new TodoListServer[rest.length + 1];
		all[0] = first;
		System.arraycopy(rest, 0, all, 1, rest.length);
		return Arrays.asList(all);
	}

}
