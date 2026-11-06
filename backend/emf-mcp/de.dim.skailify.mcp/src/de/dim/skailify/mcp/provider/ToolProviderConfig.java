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
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/** Same properties as the upstream {@code MCPToolProvider}, so a configuration moves over as is. */
@ObjectClassDefinition(name = "Skailify MCP tool provider",
		description = "Collects MCPTool services, with their annotations, for HttpMCPServerComponent")
public @interface ToolProviderConfig {

	@AttributeDefinition(description = "Name of the provider, matched by the server's toolProviders.target")
	String name();

	@AttributeDefinition(description = "What kind of tools the provider collects")
	String description();

	@AttributeDefinition(description = "LDAP filter selecting the MCPTool services")
	String tools_target();

	@AttributeDefinition(description = "How many tools must be there before the provider is registered")
	int tools_cardinality_minimum() default 1;

	@AttributeDefinition(description = "Seconds a tool call may take")
	long timeout_seconds() default 60;
}
