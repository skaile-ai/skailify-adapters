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
package de.dim.skailify.mcp.provider;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * The properties of the upstream {@code HttpMCPServerComponent} (a configuration moves over as
 * is) plus the OAuth resource server. OAuth is off by default: then the endpoint behaves exactly
 * like upstream (static {@code auth.token}, or loopback callers only).
 */
@ObjectClassDefinition(name = "Skailify MCP server",
		description = "MCP server over HTTP (as HttpMCPServerComponent) that can act as an OAuth resource server")
public @interface ServerConfig {

	// ---------------------------------------------------------------- as HttpMCPServerComponent

	@AttributeDefinition(description = "The name of the MCP server")
	String server_name() default "my-mcp-server";

	@AttributeDefinition(description = "The version of the MCP server")
	String server_version() default "1.0.0";

	@AttributeDefinition(description = "The complete URL of the MCP server, if known")
	String server_full_url() default "";

	@AttributeDefinition(description = "Servlet pattern of the endpoint, e.g. /mcp")
	String osgi_http_whiteboard_servlet_pattern();

	@AttributeDefinition(description = "LDAP filter selecting the HTTP whiteboard runtime")
	String osgi_http_whiteboard_target();

	@AttributeDefinition(description = "Whether the server offers tools")
	boolean has_tool_capability() default false;

	@AttributeDefinition(description = "Whether the server offers resources")
	boolean has_resource_capability() default false;

	@AttributeDefinition(description = "Whether the server offers prompts")
	boolean has_prompt_capability() default false;

	@AttributeDefinition(description = "Instructions for clients")
	String server_instructions() default "";

	@AttributeDefinition(description = "Seconds between keep-alive pings; 0 = none")
	long keep_alive_interval_seconds() default 0;

	@AttributeDefinition(type = AttributeType.PASSWORD, description = "Static bearer token when OAuth is off; "
			+ "empty = loopback callers only")
	String auth_token() default "";

	@AttributeDefinition(description = "LDAP filter selecting the MCPToolProvider services")
	String toolProviders_target() default "(component.name=EmptyMCPToolProvider)";

	@AttributeDefinition(description = "LDAP filter selecting the McpTokenVerifier (e.g. a JwtTokenVerifier)")
	String verifier_target() default "(verifier.name=*)";

	// ---------------------------------------------------------------- OAuth resource server

	@AttributeDefinition(description = "Whether the endpoint is an OAuth resource server: every request needs a "
			+ "bearer token the verifier accepts. Fails closed (503) while no verifier is there.")
	boolean oauth_enabled() default false;

	@AttributeDefinition(description = "The public base URL of the app, context path included "
			+ "(e.g. https://example.org/preview/abc); empty = from the request (Host, X-Forwarded-*)")
	String public_url() default "";

	@AttributeDefinition(description = "The resource identifier (RFC 8707/9728) of the endpoint; "
			+ "empty = public URL + endpoint path")
	String oauth_resource() default "";

	@AttributeDefinition(description = "Audience values a token may carry instead of the resource (e.g. a Keycloak "
			+ "audience mapper's value); the resource counts when oauth.resource or public.url is set. "
			+ "None at all = audience not checked")
	String[] oauth_audiences() default {};

	@AttributeDefinition(description = "Issuer URLs of the authorization servers, for the protected resource metadata")
	String[] oauth_authorization_servers() default {};

	@AttributeDefinition(description = "Scopes the metadata offers (scopes_supported)")
	String[] oauth_scopes_supported() default {};

	@AttributeDefinition(description = "Scopes every request needs; otherwise 403 insufficient_scope")
	String[] oauth_scopes_required() default {};

	@AttributeDefinition(description = "Human-readable name of the resource; empty = server.name")
	String oauth_resource_name() default "";
}
