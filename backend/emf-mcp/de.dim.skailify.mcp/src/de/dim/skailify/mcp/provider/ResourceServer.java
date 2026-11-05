/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.mcp.provider;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The OAuth resource server side of one MCP endpoint: its resource identifier, the audiences and
 * scopes it accepts, and its RFC 9728 protected resource metadata.
 * <p>
 * The metadata is served next to the endpoint, inside the app's context path:
 * {@code <base>/.well-known/oauth-protected-resource<endpoint>}. For an app at the host root that
 * is exactly the RFC 9728 location; under a proxy prefix (a Skaile preview) the root location is
 * not the app's to serve, so clients find it through the {@code resource_metadata} of the 401
 * challenge, which MCP clients follow first.
 * <p>
 * Upstream gap (eclipse-fennec/emf.osgi-mcp#57): meant to become optional configuration of the upstream server.
 */
final class ResourceServer {

	static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

	final boolean enabled;
	private final String publicUrl;
	private final String endpoint;
	private final String resource;
	private final Set<String> audiences;
	final List<String> authorizationServers;
	final List<String> scopesSupported;
	final List<String> scopesRequired;
	private final String resourceName;

	ResourceServer(boolean enabled, String publicUrl, String endpoint, String resource, List<String> audiences,
			List<String> authorizationServers, List<String> scopesSupported, List<String> scopesRequired,
			String resourceName) {
		this.enabled = enabled;
		this.publicUrl = stripSlash(blankToNull(publicUrl));
		this.endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
		// a configured public URL fixes the resource; only without either is it taken from the request
		String configured = blankToNull(resource);
		this.resource = configured != null ? configured : this.publicUrl != null ? this.publicUrl + this.endpoint : null;
		this.audiences = new LinkedHashSet<>(nonBlank(audiences));
		if (this.resource != null) {
			this.audiences.add(this.resource);
		}
		this.authorizationServers = nonBlank(authorizationServers);
		this.scopesSupported = nonBlank(scopesSupported);
		this.scopesRequired = nonBlank(scopesRequired);
		this.resourceName = blankToNull(resourceName);
	}

	static ResourceServer of(ServerConfig config) {
		return new ResourceServer(config.oauth_enabled(), config.public_url(), config.osgi_http_whiteboard_servlet_pattern(),
				config.oauth_resource(), Arrays.asList(config.oauth_audiences()),
				Arrays.asList(config.oauth_authorization_servers()), Arrays.asList(config.oauth_scopes_supported()),
				Arrays.asList(config.oauth_scopes_required()),
				config.oauth_resource_name().isBlank() ? config.server_name() : config.oauth_resource_name());
	}

	String endpoint() {
		return endpoint;
	}

	/**
	 * Audience values of which a token must carry one: the configured ones and the resource when
	 * it is configured (oauth.resource, or public.url + endpoint); empty = not checked
	 */
	Set<String> audiences() {
		return audiences;
	}

	/** The public base URL of the app, context path included, without a trailing slash */
	String base(HttpServletRequest request) {
		return publicUrl != null ? publicUrl : requestBase(request);
	}

	/** The resource identifier of the endpoint */
	String resource(HttpServletRequest request) {
		return resource != null ? resource : base(request) + endpoint;
	}

	/** Where the protected resource metadata is served */
	String metadataUrl(HttpServletRequest request) {
		return base(request) + METADATA_PATH + endpoint;
	}

	/** The scopes a client should ask for: the required ones, else the offered ones */
	List<String> challengeScopes() {
		return scopesRequired.isEmpty() ? scopesSupported : scopesRequired;
	}

	/** RFC 9728 protected resource metadata */
	Map<String, Object> metadata(HttpServletRequest request) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("resource", resource(request));
		metadata.put("authorization_servers", authorizationServers);
		if (!scopesSupported.isEmpty()) {
			metadata.put("scopes_supported", scopesSupported);
		}
		metadata.put("bearer_methods_supported", List.of("header"));
		if (resourceName != null) {
			metadata.put("resource_name", resourceName);
		}
		return metadata;
	}

	/**
	 * The base URL as the caller sees it: the forwarded scheme and host when a proxy says so,
	 * then the request's own, plus the context path.
	 */
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
			int port = request.getServerPort();
			boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
			host = request.getServerName() + (defaultPort || port <= 0 ? "" : ":" + port);
		}
		String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
		return stripSlash(scheme + "://" + host + contextPath);
	}

	private static String firstValue(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		int comma = header.indexOf(',');
		return (comma < 0 ? header : header.substring(0, comma)).strip();
	}

	private static String stripSlash(String url) {
		return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static List<String> nonBlank(List<String> values) {
		return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
	}
}
