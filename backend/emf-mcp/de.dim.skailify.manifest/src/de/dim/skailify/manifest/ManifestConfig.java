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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/** One app's Skailify manifest, {@code /.well-known/skailify.json} of an HTTP context. */
@ObjectClassDefinition(name = "Skailify manifest",
		description = "Serves /.well-known/skailify.json: the app, its embed URL, MCP endpoint, auth and grant catalogue")
public @interface ManifestConfig {

	@AttributeDefinition(description = "Stable id of the app, e.g. shop")
	String app_id();

	@AttributeDefinition(description = "Name shown to people")
	String app_name();

	@AttributeDefinition(description = "What the app is for")
	String app_description() default "";

	@AttributeDefinition(description = "Public base URL of the app, context path included; empty = from the request")
	String public_url() default "";

	@AttributeDefinition(description = "The page Skaile embeds, absolute or relative to the base URL")
	String embed_url() default "/";

	@AttributeDefinition(description = "The MCP endpoint, absolute or relative to the base URL")
	String mcp_endpoint() default "/mcp";

	@AttributeDefinition(description = "Whether the MCP endpoint is an OAuth resource server (as its server's oauth.enabled)")
	boolean oauth_enabled() default false;

	@AttributeDefinition(description = "Issuer URLs tokens for the app come from")
	String[] auth_issuers() default {};

	@AttributeDefinition(description = "Scopes the app knows")
	String[] auth_scopes() default {};

	@AttributeDefinition(description = "LDAP filter selecting the MCPTool services listed in the grant catalogue")
	String tools_target() default "(tool.name=*)";

	@AttributeDefinition(description = "Further actions (app.* capabilities of the UI or a server-side connection), "
			+ "each a JSON object: {\"name\", \"kind\": query|effect, \"effectClass\": routine|external|privileged, "
			+ "\"approval\": none|optional|required, \"description\"}")
	String[] actions() default {};

	@AttributeDefinition(description = "LDAP filter selecting the HTTP whiteboard runtime")
	String osgi_http_whiteboard_target();
}
