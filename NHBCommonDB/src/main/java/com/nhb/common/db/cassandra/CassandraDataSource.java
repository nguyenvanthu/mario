package com.nhb.common.db.cassandra;

import java.io.Closeable;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Cluster.Builder;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.nhb.common.vo.HostAndPort;

public class CassandraDataSource implements Closeable {

	private Cluster cluster;
	private Session session;
	private String keyspace;

	private final Collection<HostAndPort> endpoints = new HashSet<>();
	private final Map<String, PreparedStatement> cachedStatements = new ConcurrentHashMap<>();

	public CassandraDataSource() {
		// do nothing
	}

	public CassandraDataSource(String keyspace) {
		this();
		this.setKeyspace(keyspace);
	}

	public CassandraDataSource(String keyspace, Collection<HostAndPort> endpoints) {
		this(endpoints);
		this.setKeyspace(keyspace);
	}

	public CassandraDataSource(String keyspace, HostAndPort... endpoints) {
		this(endpoints);
		this.setKeyspace(keyspace);
	}

	public CassandraDataSource(Collection<HostAndPort> endpoints) {
		this();
		this.endpoints.addAll(endpoints);
	}

	public CassandraDataSource(HostAndPort... endpoints) {
		this(Arrays.asList(endpoints));
	}

	public boolean isConnected() {
		return this.session != null;
	}

	public void reset() {
		this.close();
		this.endpoints.clear();
	}

	public void addEndpoints(Collection<HostAndPort> endpoints) {
		if (endpoints == null) {
			return;
		}
		if (this.isConnected()) {
			throw new IllegalStateException("Cannot add endpoint(s) when cluster is being connected");
		}
		this.endpoints.addAll(endpoints);
	}

	public void addEndpoints(HostAndPort... endpoints) {
		this.addEndpoints(Arrays.asList(endpoints));
	}

	public void removeEndpoint(HostAndPort... endpoints) {
		this.removeEndpoints(Arrays.asList(endpoints));
	}

	public void removeEndpoints(Collection<HostAndPort> endpoints) {
		if (this.isConnected()) {
			throw new IllegalStateException("Cannot remove endpoint(s) when cluster is being connected");
		}
		if (endpoints != null) {
			for (HostAndPort endpoint : endpoints) {
				this.endpoints.remove(endpoint);
			}
		}
	}

	public void connect(Collection<HostAndPort> endpoints) {
		this.endpoints.addAll(endpoints);
		this.connect();
	}

	public void connect() {
		if (this.endpoints.size() == 0) {
			throw new RuntimeException("No endpoint defined");
		}
		Builder builder = new Builder();
		for (HostAndPort endpoint : this.endpoints) {
			builder.addContactPoint(endpoint.toString());
		}
		this.cluster = builder.build();
		this.session = this.cluster.connect();
	}

	@Override
	public void close() {
		if (!this.isConnected()) {
			return;
		}
		this.session.close();
		this.session = null;
		this.cachedStatements.clear();
	}

	public ResultSet execute(Statement statement) {
		if (this.isConnected()) {
			return this.session.execute(statement);
		}
		throw new IllegalStateException("Cluster not connected");
	}

	public ResultSet execute(String cql) {
		BoundStatement statement = new BoundStatement(this.getPreparedStatement(cql));
		return this.execute(statement);
	}

	public PreparedStatement getPreparedStatement(String cql) {
		if (!this.isConnected()) {
			this.connect();
		}
		if (!this.cachedStatements.containsKey(cql)) {
			this.cachedStatements.put(cql, this.session.prepare(cql));
		}
		return this.cachedStatements.get(cql);
	}

	public String getKeyspace() {
		return keyspace;
	}

	public void setKeyspace(String keyspace) {
		this.keyspace = keyspace;
	}
}
