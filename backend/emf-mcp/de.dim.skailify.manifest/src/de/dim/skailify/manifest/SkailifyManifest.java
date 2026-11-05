/**
 * Copyright (c) 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package de.dim.skailify.manifest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import de.dim.skailify.mcp.AnnotatedMCPTool;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves the Skailify manifest of an app at {@code /.well-known/skailify.json} of its HTTP
 * context (under the context path, too): who the app is, the page Skaile embeds, the MCP
 * endpoint, where tokens come from, and the grant catalogue.
 * <p>
 * The catalogue lists every {@link MCPTool} the configuration selects. What a tool is comes from
 * what it says about itself, nothing Skailify-specific: its {@code readOnlyHint} (a query, else
 * an effect), its service properties {@value #SCOPE_PROPERTY} (the scope it needs) and
 * {@value #EPACKAGE_PROPERTY} (the nsURI of the EPackage it changes). For an effect on an
 * EPackage, effect class, approval and the refinements per type come from the
 * {@code skailify} EAnnotation of that EPackage ({@link GrantAnnotations}); the EPackage is taken
 * from the EPackage services by their {@code emf.nsURI}. Actions that are no MCP tools are
 * declared in the configuration.
 * <p>
 * The format is a draft ({@value #FORMAT}): it is aligned with skaile-ai/platform#9034 (manifest
 * import) and the ADRs in skaile-ai/platform#9020 once they settle. See
 * {@code docs/skailify-manifest.md}.
 */
@Component(name = "SkailifyManifest", configurationPid = "SkailifyManifest", configurationPolicy = ConfigurationPolicy.REQUIRE,
		service = {})
@Designate(ocd = ManifestConfig.class, factory = true)
public class SkailifyManifest {

	static final String PATH = "/.well-known/skailify.json";
	static final String FORMAT = "0.1-draft";
	static final String PROTECTED_RESOURCE_PATH = "/.well-known/oauth-protected-resource";
	/** As de.dim.skailify.emf.tools.ModelTools sets them */
	static final String SCOPE_PROPERTY = "tool.scope";
	static final String EPACKAGE_PROPERTY = "tool.epackage";

	private static final Logger LOG = Logger.getLogger(SkailifyManifest.class.getName());
	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	/** What the configuration says, apart from the tools */
	record Settings(String appId, String appName, String appDescription, String publicUrl, String embedUrl,
			String mcpEndpoint, boolean oauth, List<String> issuers, List<String> scopes, List<ToolGrant> actions) {
	}

	/** A tool as the manifest sees it: the service and its properties */
	record Tool(MCPTool tool, String scope, String ePackage) {
	}

	private final Map<MCPTool, Tool> tools = new ConcurrentHashMap<>();
	private final Map<String, EPackage> ePackages = new ConcurrentHashMap<>();
	private final Settings settings;
	private final ServiceRegistration<Servlet> registration;

	@Activate
	public SkailifyManifest(ManifestConfig config, BundleContext context, @Reference McpJsonMapperSupplier jsonSupplier) {
		McpJsonMapper json = jsonSupplier.get();
		settings = new Settings(config.app_id(), config.app_name(), config.app_description(), config.public_url(),
				config.embed_url(), config.mcp_endpoint(), config.oauth_enabled(), nonBlank(config.auth_issuers()),
				nonBlank(config.auth_scopes()), actions(config.actions(), json));
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("osgi.http.whiteboard.servlet.pattern", PATH);
		properties.put("osgi.http.whiteboard.servlet.name", "skailify-manifest-" + config.app_id());
		properties.put("osgi.http.whiteboard.target", config.osgi_http_whiteboard_target());
		registration = context.registerService(Servlet.class, new ManifestServlet(json), properties);
	}

	@Deactivate
	void deactivate() {
		try {
			registration.unregister();
		} catch (IllegalStateException e) {
			// gone with the bundle already
		}
	}

	@Reference(name = "tools", cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
			policyOption = ReferencePolicyOption.GREEDY)
	void addTool(MCPTool tool, Map<String, Object> properties) {
		tools.put(tool, new Tool(tool, string(properties.get(SCOPE_PROPERTY), null), string(properties.get(EPACKAGE_PROPERTY), null)));
	}

	void removeTool(MCPTool tool) {
		tools.remove(tool);
	}

	@Reference(name = "ePackages", cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
			policyOption = ReferencePolicyOption.GREEDY)
	void addEPackage(EPackage ePackage) {
		ePackages.put(ePackage.getNsURI(), ePackage);
	}

	void removeEPackage(EPackage ePackage) {
		ePackages.remove(ePackage.getNsURI(), ePackage);
	}

	private final class ManifestServlet extends HttpServlet {

		private static final long serialVersionUID = 1L;
		private final transient McpJsonMapper json;

		ManifestServlet(McpJsonMapper json) {
			this.json = json;
		}

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			String base = settings.publicUrl().isBlank() ? requestBase(request) : strip(settings.publicUrl());
			byte[] body = json.writeValueAsBytes(document(settings, base, tools.values(), ePackages::get));
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("application/json");
			response.setHeader("Access-Control-Allow-Origin", "*");
			response.setHeader("Cache-Control", "max-age=300");
			response.setContentLength(body.length);
			response.getOutputStream().write(body);
		}
	}

	static Map<String, Object> document(Settings settings, String base, Collection<Tool> tools,
			Function<String, EPackage> ePackages) {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("skailify", FORMAT);
		document.put("status", "Draft. To be aligned with skaile-ai/platform#9034 and the ADRs in skaile-ai/platform#9020.");
		Map<String, Object> app = new LinkedHashMap<>();
		app.put("id", settings.appId());
		app.put("name", settings.appName());
		if (!settings.appDescription().isBlank()) {
			app.put("description", settings.appDescription());
		}
		app.put("url", base + "/");
		app.put("embedUrl", resolve(base, settings.embedUrl()));
		document.put("app", app);

		String endpoint = resolve(base, settings.mcpEndpoint());
		Map<String, Object> mcp = new LinkedHashMap<>();
		mcp.put("url", endpoint);
		mcp.put("transport", "streamable-http");
		document.put("mcp", mcp);

		Map<String, Object> auth = new LinkedHashMap<>();
		if (settings.oauth()) {
			auth.put("type", "oauth2");
			auth.put("issuers", settings.issuers());
			if (!settings.scopes().isEmpty()) {
				auth.put("scopes", settings.scopes());
			}
			String path = endpoint.startsWith(base) ? endpoint.substring(base.length()) : "";
			auth.put("protectedResourceMetadata", base + PROTECTED_RESOURCE_PATH + path);
		} else {
			auth.put("type", "none");
		}
		document.put("auth", auth);

		List<Map<String, Object>> grants = new ArrayList<>();
		tools.stream().map(tool -> grant(tool, ePackages)).sorted(Comparator.comparing(ToolGrant::name))
				.forEach(grant -> grants.add(entry(grant, "mcp")));
		settings.actions().stream().sorted(Comparator.comparing(ToolGrant::name))
				.forEach(grant -> grants.add(entry(grant, "app")));
		document.put("grants", grants);
		return document;
	}

	private static Map<String, Object> entry(ToolGrant grant, String channel) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("name", grant.name());
		entry.put("channel", channel);
		entry.putAll(grant.toMap());
		return entry;
	}

	/**
	 * A query when the tool says it only reads; an effect on its EPackage, from that package's
	 * annotations, when it names one we know; otherwise a routine effect that needs approval
	 */
	static ToolGrant grant(Tool tool, Function<String, EPackage> ePackages) {
		MCPTool mcpTool = tool.tool();
		String description = mcpTool.getDescription();
		boolean readOnly = false;
		if (mcpTool instanceof AnnotatedMCPTool annotated) {
			ToolAnnotations annotations = annotated.getAnnotations();
			String title = annotated.getTitle() != null ? annotated.getTitle() : annotations != null ? annotations.title() : null;
			if (title != null) {
				description = title;
			}
			readOnly = annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint());
		}
		if (readOnly) {
			return ToolGrant.query(mcpTool.getName(), tool.scope(), description);
		}
		EPackage ePackage = tool.ePackage() == null ? null : ePackages.apply(tool.ePackage());
		if (ePackage != null) {
			return GrantAnnotations.effect(mcpTool.getName(), tool.scope(), description, ePackage);
		}
		return new ToolGrant(mcpTool.getName(), ToolGrant.EFFECT, ToolGrant.ROUTINE, ToolGrant.REQUIRED, tool.scope(),
				description, List.of());
	}

	static List<ToolGrant> actions(String[] declared, McpJsonMapper json) {
		List<ToolGrant> actions = new ArrayList<>();
		for (String text : declared) {
			if (text == null || text.isBlank()) {
				continue;
			}
			try {
				Map<String, Object> action = json.readValue(text, MAP);
				String kind = string(action.get("kind"), ToolGrant.EFFECT);
				String name = string(action.get("name"), null);
				if (name == null) {
					throw new IllegalArgumentException("an action needs a name");
				}
				actions.add(new ToolGrant(name, kind,
						string(action.get("effectClass"), ToolGrant.ROUTINE),
						string(action.get("approval"), ToolGrant.QUERY.equals(kind) ? ToolGrant.NONE : ToolGrant.REQUIRED),
						string(action.get("scope"), null), string(action.get("description"), null), List.of()));
			} catch (IOException | IllegalArgumentException e) {
				LOG.warning(() -> "Skailify manifest: ignoring the action " + text + ": " + e.getMessage());
			}
		}
		return actions;
	}

	/** The base URL as the caller sees it: forwarded scheme and host first, plus the context path */
	static String requestBase(HttpServletRequest request) {
		String scheme = firstValue(request.getHeader("X-Forwarded-Proto"));
		if (scheme == null) {
			scheme = request.getScheme();
		}
		String host = firstValue(request.getHeader("X-Forwarded-Host"));
		if (host == null) {
			host = request.getHeader("Host");
		}
		if (host == null) {
			host = request.getServerName() + (request.getServerPort() > 0 ? ":" + request.getServerPort() : "");
		}
		String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
		return strip(scheme + "://" + host + contextPath);
	}

	private static String firstValue(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		int comma = header.indexOf(',');
		return (comma < 0 ? header : header.substring(0, comma)).strip();
	}

	private static String string(Object value, String fallback) {
		return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
	}

	private static String resolve(String base, String url) {
		if (url.startsWith("http://") || url.startsWith("https://")) {
			return url;
		}
		return base + (url.startsWith("/") ? url : "/" + url);
	}

	private static String strip(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private static List<String> nonBlank(String[] values) {
		return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
	}
}
