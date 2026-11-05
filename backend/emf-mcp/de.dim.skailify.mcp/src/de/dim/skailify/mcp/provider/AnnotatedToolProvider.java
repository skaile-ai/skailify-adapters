/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.mcp.provider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.fennec.mcp.api.MCPServerConstants;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.api.MCPToolProvider;
import org.osgi.annotation.bundle.Capability;
import org.osgi.namespace.implementation.ImplementationNamespace;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;

import de.dim.skailify.mcp.AnnotatedMCPTool;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Collects {@link MCPTool} services like the upstream {@code MCPToolProvider} (factory PID
 * {@code MCPToolProvider}, same properties) and adds what {@link AnnotatedMCPTool} says about a
 * tool to its definition: the title and the tool annotations. A plain {@link MCPTool} is served
 * without them. Tool calls run on the bounded-elastic scheduler with a timeout; a failure becomes
 * an {@code isError} result with its message instead of a JSON-RPC error, so the agent can read
 * it.
 * <p>
 * Upstream gap: once {@code MCPTool} carries annotations, the upstream provider replaces this one.
 */
@Component(name = "SkailifyToolProvider", service = MCPToolProvider.class, configurationPid = "SkailifyToolProvider",
		configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = ToolProviderConfig.class, factory = true)
@Capability(namespace = ImplementationNamespace.IMPLEMENTATION_NAMESPACE,
		name = MCPServerConstants.MCP_TOOL_PROVIDER_IMPLEMENTATION, version = MCPServerConstants.MCP_TOOL_PROVIDER_VERSION)
public class AnnotatedToolProvider implements MCPToolProvider {

	private static final Logger LOG = Logger.getLogger(AnnotatedToolProvider.class.getName());

	private final List<MCPTool> tools = new CopyOnWriteArrayList<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private final String description;
	private final Duration timeout;
	private final McpJsonMapper json;

	@Activate
	public AnnotatedToolProvider(ToolProviderConfig config, @Reference McpJsonMapperSupplier json) {
		this.description = config.description();
		this.timeout = Duration.ofSeconds(Math.max(1, config.timeout_seconds()));
		this.json = json.get();
	}

	@Reference(name = "tools", cardinality = ReferenceCardinality.AT_LEAST_ONE, policy = ReferencePolicy.DYNAMIC)
	void addTool(MCPTool tool) {
		tools.add(tool);
		changed();
	}

	void removeTool(MCPTool tool) {
		tools.remove(tool);
		changed();
	}

	@Override
	public List<AsyncToolSpecification> getMCPTools() {
		return tools.stream().map(this::specification).toList();
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public void onToolsChanged(Runnable listener) {
		if (listener != null && !listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	@Override
	public void removeToolsChangedListener(Runnable listener) {
		listeners.remove(listener);
	}

	private void changed() {
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, e, () -> "A tool change listener of provider " + description + " failed");
			}
		}
	}

	AsyncToolSpecification specification(MCPTool tool) {
		Tool.Builder definition = Tool.builder(tool.getName(), json, tool.getInputSchema()).description(tool.getDescription());
		if (tool.getOutputSchema() != null) {
			definition.outputSchema(json, tool.getOutputSchema());
		}
		if (tool instanceof AnnotatedMCPTool annotated) {
			String title = annotated.getTitle();
			ToolAnnotations annotations = annotated.getAnnotations();
			if (title != null) {
				definition.title(title);
			}
			if (annotations != null) {
				definition.annotations(annotations.title() != null || title == null ? annotations
						: new ToolAnnotations(title, annotations.readOnlyHint(), annotations.destructiveHint(),
								annotations.idempotentHint(), annotations.openWorldHint(), annotations.returnDirect()));
			}
		}
		return AsyncToolSpecification.builder().tool(definition.build())
				.callHandler((exchange, request) -> Mono.defer(() -> tool.execute(exchange, request.arguments()))
						.subscribeOn(Schedulers.boundedElastic())
						.timeout(timeout)
						.onErrorResume(e -> {
							LOG.log(Level.FINE, e, () -> "Tool " + tool.getName() + " failed");
							return Mono.just(CallToolResult.builder().isError(true)
									.addTextContent(tool.getName() + " failed: " + e.getMessage()).build());
						}))
				.build();
	}
}
