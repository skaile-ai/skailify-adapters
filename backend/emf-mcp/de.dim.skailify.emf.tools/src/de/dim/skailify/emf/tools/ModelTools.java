/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf.tools;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.persistence.repository.api.Repository;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentServiceObjects;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceScope;
import org.osgi.service.metatype.annotations.Designate;

import io.modelcontextprotocol.json.McpJsonMapperSupplier;

/**
 * One set of model tools per factory configuration ({@code SkailifyEmfTools~<name>}): for the
 * EPackage and the Repository the configuration selects, it registers the MCP tools
 * {@code describe_model}, {@code query}, {@code get} and, unless read-only, {@code create},
 * {@code update}, {@code delete} as {@link MCPTool} services. Each carries {@code tool.name} and
 * the configuration's {@code skailify.model}, which a tool provider selects them by:
 *
 * <pre>
 * "SkailifyEmfTools~example": {
 *   "skailify.model": "example",
 *   "epackage.target": "(emf.nsURI=https://example.org/model/1.0)",
 *   "repository.target": "(persistence.repository.id=example)"
 * },
 * "SkailifyToolProvider~example": {
 *   "name": "example_tools", "description": "...",
 *   "tools.target": "(skailify.model=example)", "tools.cardinality.minimum:Integer": 6
 * }
 * </pre>
 *
 * Reading and changing are separable: every tool also carries {@value #READ_ONLY_PROPERTY}
 * ({@code true} for describe_model, query and get, {@code false} for create, update and delete,
 * matching its {@code readOnlyHint}). A deployment that serves reads only selects them with
 * {@code "tools.target": "(&(skailify.model=example)(tool.readOnly=true))"} and
 * {@code "tools.cardinality.minimum:Integer": 3}; changes that need a person's consent are then
 * left to a channel of their own. {@code read_only} drops the change tools altogether.
 * <p>
 * Each tool also says what it works on and needs, for whoever describes the tools (e.g. a
 * catalogue of grants): {@value #EPACKAGE_PROPERTY} (the EPackage's nsURI) and, when a scope is
 * configured, {@value #SCOPE_PROPERTY}.
 *
 * Nothing in here knows a model: whatever the EPackage says is what the tools offer. Each call
 * works on a repository instance of its own.
 */
@Component(name = "SkailifyEmfTools", configurationPid = "SkailifyEmfTools", configurationPolicy = ConfigurationPolicy.REQUIRE,
		service = {})
@Designate(ocd = ModelToolsConfig.class, factory = true)
public class ModelTools {

	/** The service property of the tools naming their tool set */
	public static final String MODEL_PROPERTY = "skailify.model";

	/** The service property telling a tool that only reads ({@code true}) from one that changes data */
	public static final String READ_ONLY_PROPERTY = "tool.readOnly";

	/** The service property naming the nsURI of the EPackage the tool works on */
	public static final String EPACKAGE_PROPERTY = "tool.epackage";

	/** The service property naming the OAuth scope a caller needs (scope.read / scope.write), when one is configured */
	public static final String SCOPE_PROPERTY = "tool.scope";

		private final List<ServiceRegistration<MCPTool>> registrations = new ArrayList<>();

	@Activate
	public ModelTools(ModelToolsConfig config, BundleContext context,
			@Reference(name = "epackage") EPackage ePackage,
			@Reference(name = "repository", scope = ReferenceScope.PROTOTYPE_REQUIRED) ComponentServiceObjects<Repository> repositories,
			@Reference McpJsonMapperSupplier json) {
		ModelContext model = new ModelContext(config, ePackage, repositories, json.get());
		List<ModelTool> tools = new ArrayList<>(List.of(new DescribeModelTool(model), new QueryTool(model), new GetTool(model)));
		if (!config.read_only()) {
			tools.addAll(List.of(new CreateTool(model), new UpdateTool(model), new DeleteTool(model)));
		}
		for (ModelTool tool : tools) {
			Dictionary<String, Object> properties = new Hashtable<>();
			properties.put("tool.name", tool.getName());
			properties.put(MODEL_PROPERTY, config.skailify_model());
			properties.put(READ_ONLY_PROPERTY, Boolean.TRUE.equals(tool.getAnnotations().readOnlyHint()));
			properties.put(EPACKAGE_PROPERTY, ePackage.getNsURI());
			if (!tool.requiredScope().isBlank()) {
				properties.put(SCOPE_PROPERTY, tool.requiredScope());
			}
			registrations.add(context.registerService(MCPTool.class, tool, properties));
		}
	}

	@Deactivate
	void deactivate() {
		registrations.forEach(registration -> {
			try {
				registration.unregister();
			} catch (IllegalStateException e) {
				// gone with the bundle already
			}
		});
		registrations.clear();
	}
}
