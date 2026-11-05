/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.mcp.provider;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

/**
 * Starts every configured MCP server. {@code HttpMCPServerComponent} is a delayed component: it
 * only registers its servlet once someone gets its {@link MCPServer} service, so a runtime that
 * merely configures one serves nothing. Holding all of them activates them.
 * <p>
 * Upstream gap: the upstream server component should be immediate (the gogo and EMF runtimes
 * of emf.osgi-mcp work around it with activator components of their own).
 */
@Component(immediate = true)
public class ServerStarter {

	private static final Logger LOG = Logger.getLogger(ServerStarter.class.getName());

	private final List<MCPServer> servers = new CopyOnWriteArrayList<>();

	@Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
			policyOption = ReferencePolicyOption.GREEDY)
	void addServer(MCPServer server) {
		servers.add(server);
		LOG.info(() -> "MCP server " + server.getServerName() + " started");
	}

	void removeServer(MCPServer server) {
		servers.remove(server);
	}
}
