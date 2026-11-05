/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.mcp.provider;

import java.time.Duration;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.eclipse.fennec.mcp.api.AbstractHttpMCPServer;
import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.MCPToolProvider;
import org.eclipse.fennec.mcp.api.auth.McpTokenVerifier;
import org.eclipse.fennec.mcp.endpoint.MCPEndpoint;
import org.osgi.annotation.bundle.Requirement;
import org.osgi.annotation.bundle.Requirements;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import de.dim.skailify.mcp.Caller;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Drop-in for the upstream {@code HttpMCPServerComponent} (factory PID {@code SkailifyMCPServer},
 * the same properties, see {@link ServerConfig}) that hands the caller to the tools and, with
 * {@code oauth.enabled}, is an OAuth resource server for MCP clients such as Skaile's per-user
 * MCP OAuth:
 * <ul>
 * <li>the transport gets a context extractor that puts the verified {@link Caller} into the
 * {@code McpTransportContext}, where a tool reads it with {@link Caller#of};</li>
 * <li>a {@link BearerFilter} in front of the upstream authentication filter answers with RFC 6750
 * challenges naming the RFC 9728 metadata, and checks audience and required scopes;</li>
 * <li>the protected resource metadata is served at
 * {@code /.well-known/oauth-protected-resource<endpoint>} of the same HTTP context.</li>
 * </ul>
 * With OAuth off it serves exactly like upstream: static token, or loopback callers only.
 * <p>
 * Upstream gap (eclipse-fennec/emf.osgi-mcp#56, #57): {@link AbstractHttpMCPServer} builds its transport
 * without a context extractor and keeps the names of the registered tools private, so
 * {@link #initializeMCPServer()} and {@link #syncTools()} are re-done here, as upstream has them.
 * Once upstream takes an extractor and the metadata, this component goes away.
 */
@Component(name = "SkailifyMCPServer", service = { MCPServer.class, MCPEndpoint.class },
		configurationPid = "SkailifyMCPServer", configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = ServerConfig.class, factory = true)
@Requirements({
		@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "io.modelcontextprotocol.sdk.mcp-json-jackson3"),
		@Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE, name = "com.networknt.json-schema-validator") })
public class SkailifyMCPServer extends AbstractHttpMCPServer {

	private static final Logger LOG = Logger.getLogger(SkailifyMCPServer.class.getName());

	private final ServerConfig config;
	private final ResourceServer resourceServer;
	private final McpJsonMapper jsonMapper;
	private final JsonSchemaValidator schemaValidator;
	private final List<MCPToolProvider> toolProviders;
	private final Set<String> registeredToolNames = ConcurrentHashMap.newKeySet();
	private final Runnable toolsChanged = this::syncTools;

	@Reference(name = "verifier", cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC,
			policyOption = ReferencePolicyOption.GREEDY)
	private volatile McpTokenVerifier tokenVerifier;

	private ServiceRegistration<?> bearerFilter;
	private ServiceRegistration<?> metadataServlet;

	@Activate
	public SkailifyMCPServer(ServerConfig config, BundleContext context, @Reference McpJsonMapperSupplier jsonMapper,
			@Reference JsonSchemaValidatorSupplier schemaValidator,
			@Reference(name = "toolProviders", cardinality = ReferenceCardinality.AT_LEAST_ONE) List<MCPToolProvider> toolProviders) {
		this.config = config;
		this.context = context;
		this.resourceServer = ResourceServer.of(config);
		this.jsonMapper = jsonMapper.get();
		this.schemaValidator = schemaValidator.get();
		this.toolProviders = toolProviders;
		if (resourceServer.enabled) {
			if (resourceServer.audiences().isEmpty()) {
				LOG.warning(() -> "MCP server " + config.server_name() + ": OAuth is on without oauth.resource or "
						+ "oauth.audiences; the audience of tokens is not checked");
			}
			if (resourceServer.authorizationServers.isEmpty()) {
				LOG.warning(() -> "MCP server " + config.server_name() + ": OAuth is on without "
						+ "oauth.authorization.servers; clients cannot discover where to get a token");
			}
		}
		initializeMCPServer();
		toolProviders.forEach(provider -> provider.onToolsChanged(toolsChanged));
		LOG.info(() -> "MCP server " + config.server_name() + " at " + resourceServer.endpoint()
				+ (resourceServer.enabled ? " (OAuth resource server, issuers " + resourceServer.authorizationServers + ")" : ""));
	}

	@Deactivate
	void deactivate() {
		toolProviders.forEach(provider -> provider.removeToolsChangedListener(toolsChanged));
		unregisterMCPServer();
	}

	/** As upstream, with a context extractor handing the caller to the tools (upstream gap, eclipse-fennec/emf.osgi-mcp#56) */
	@Override
	protected void initializeMCPServer() {
		HttpServletStreamableServerTransportProvider.Builder builder = HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(getJsonMapper())
				.mcpEndpoint(getEndpointPath())
				.contextExtractor(SkailifyMCPServer::transportContext);
		if (getKeepAliveIntervalSeconds() > 0) {
			builder.keepAliveInterval(Duration.ofSeconds(getKeepAliveIntervalSeconds()));
		}
		HttpServletStreamableServerTransportProvider transport = builder.build();
		registerHttpWhiteboard(transport, context);
		List<AsyncToolSpecification> tools = distinctTools();
		mcpServer = McpServer.async(transport)
				.serverInfo(getServerName(), getServerVersion())
				.jsonMapper(getJsonMapper())
				.jsonSchemaValidator(getSchemaValidator())
				.capabilities(buildCapabilities())
				.tools(tools)
				.prompts(getPrompts())
				.resources(getResources())
				.requestTimeout(Duration.ofMinutes(10))
				.instructions(getInstructions())
				.build();
		registeredToolNames.clear();
		tools.forEach(spec -> registeredToolNames.add(spec.tool().name()));
	}

	static McpTransportContext transportContext(HttpServletRequest request) {
		return request.getAttribute(Caller.KEY) instanceof Caller caller
				? McpTransportContext.create(Map.of(Caller.KEY, caller))
				: McpTransportContext.EMPTY;
	}

	/** As upstream, over this class's own record of the registered tools */
	@Override
	protected synchronized void syncTools() {
		if (mcpServer == null || !hasToolCapability()) {
			return;
		}
		Map<String, AsyncToolSpecification> current = new LinkedHashMap<>();
		getTools().forEach(spec -> current.putIfAbsent(spec.tool().name(), spec));
		for (String name : List.copyOf(registeredToolNames)) {
			if (!current.containsKey(name)) {
				registeredToolNames.remove(name);
				mcpServer.removeTool(name).subscribe();
			}
		}
		current.forEach((name, spec) -> {
			if (registeredToolNames.add(name)) {
				mcpServer.addTool(spec).subscribe();
			}
		});
	}

	/**
	 * The bearer filter first, so the endpoint is never reachable without it, then upstream's
	 * filters and servlet, then the protected resource metadata.
	 */
	@Override
	protected void registerHttpWhiteboard(HttpServletStreamableServerTransportProvider transport, BundleContext context) {
		Dictionary<String, Object> filter = new Hashtable<>();
		filter.put("osgi.http.whiteboard.filter.pattern", getEndpointPath());
		filter.put("osgi.http.whiteboard.filter.name", getServerName() + "-bearer");
		filter.put("osgi.http.whiteboard.filter.asyncSupported", true);
		filter.put("osgi.http.whiteboard.target", config.osgi_http_whiteboard_target());
		// before upstream's McpAuthenticationFilter (ranking 0)
		filter.put(Constants.SERVICE_RANKING, 1000);
		bearerFilter = context.registerService(Filter.class, new BearerFilter(resourceServer, () -> tokenVerifier, jsonMapper),
				filter);
		super.registerHttpWhiteboard(transport, context);
		if (resourceServer.enabled) {
			Dictionary<String, Object> servlet = new Hashtable<>();
			servlet.put("osgi.http.whiteboard.servlet.pattern", new String[] { ResourceServer.METADATA_PATH,
					ResourceServer.METADATA_PATH + resourceServer.endpoint() });
			servlet.put("osgi.http.whiteboard.servlet.name", getServerName() + "-protected-resource");
			servlet.put("osgi.http.whiteboard.target", config.osgi_http_whiteboard_target());
			metadataServlet = context.registerService(Servlet.class, new JsonServlet(resourceServer::metadata, jsonMapper),
					servlet);
		}
	}

	@Override
	protected void unregisterMCPServer() {
		super.unregisterMCPServer();
		for (ServiceRegistration<?> registration : new ServiceRegistration<?>[] { metadataServlet, bearerFilter }) {
			if (registration != null) {
				try {
					registration.unregister();
				} catch (IllegalStateException e) {
					// gone with the bundle already
				}
			}
		}
		metadataServlet = null;
		bearerFilter = null;
	}

	@Override
	public List<AsyncToolSpecification> getTools() {
		return toolProviders.stream().map(MCPToolProvider::getMCPTools).flatMap(List::stream).toList();
	}

	@Override
	public List<AsyncPromptSpecification> getPrompts() {
		return Collections.emptyList();
	}

	@Override
	public List<AsyncResourceSpecification> getResources() {
		return Collections.emptyList();
	}

	@Override
	public String getServerName() {
		return config.server_name();
	}

	@Override
	public String getServerFullUrl() {
		return config.server_full_url();
	}

	@Override
	protected Dictionary<String, Object> getServletProperties() {
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("osgi.http.whiteboard.servlet.pattern", config.osgi_http_whiteboard_servlet_pattern());
		properties.put("osgi.http.whiteboard.servlet.name", config.server_name());
		properties.put("osgi.http.whiteboard.servlet.asyncSupported", true);
		properties.put("osgi.http.whiteboard.target", config.osgi_http_whiteboard_target());
		return properties;
	}

	@Override
	protected String getServerVersion() {
		return config.server_version();
	}

	@Override
	protected String getEndpointPath() {
		return config.osgi_http_whiteboard_servlet_pattern();
	}

	@Override
	protected boolean hasToolCapability() {
		return config.has_tool_capability();
	}

	@Override
	protected boolean hasPromptCapability() {
		return config.has_prompt_capability();
	}

	@Override
	protected boolean hasResourceCapability() {
		return config.has_resource_capability();
	}

	@Override
	protected String getInstructions() {
		return config.server_instructions().isBlank() ? null : config.server_instructions();
	}

	@Override
	protected JsonSchemaValidator getSchemaValidator() {
		return schemaValidator;
	}

	@Override
	protected McpJsonMapper getJsonMapper() {
		return jsonMapper;
	}

	/** With OAuth on, the static token is never a way in */
	@Override
	protected String getAuthToken() {
		return resourceServer.enabled ? null : config.auth_token();
	}

	/** With OAuth off a bound verifier is not used either: off means as before, static token or loopback */
	@Override
	protected McpTokenVerifier getTokenVerifier() {
		return resourceServer.enabled ? tokenVerifier : null;
	}

	@Override
	protected long getKeepAliveIntervalSeconds() {
		return config.keep_alive_interval_seconds();
	}
}
