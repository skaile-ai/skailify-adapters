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
/**
 * MCP tools that say how they behave. An {@link de.dim.skailify.mcp.AnnotatedMCPTool} is an
 * ordinary {@code org.eclipse.fennec.mcp.api.MCPTool} with a title and MCP tool annotations
 * ({@code readOnlyHint}, {@code destructiveHint}, {@code idempotentHint}, {@code openWorldHint}).
 * The {@code SkailifyToolProvider} factory component collects tools like the upstream
 * {@code MCPToolProvider} and puts these annotations into {@code tools/list}; a client (or a
 * governing proxy such as Skaile) uses them to tell queries from effects.
 * <p>
 * The {@code SkailifyMCPServer} factory component is a drop-in for the upstream
 * {@code HttpMCPServerComponent} that, with OAuth on, acts as an OAuth resource server: it
 * serves RFC 9728 protected resource metadata next to the endpoint, answers 401/403 with a
 * {@code WWW-Authenticate} challenge, checks audience and scopes, and hands the verified
 * {@link de.dim.skailify.mcp.Caller} to the tools.
 * <p>
 * Nothing in here knows an application; the bundle is meant to move as a whole to a repository
 * of the generic MCP extensions (a fork of eclipse-fennec/emf.osgi-mcp).
 * <p>
 * Upstream gap: the upstream {@code MCPTool} has no annotations (eclipse-fennec/emf.osgi-mcp#55).
 */
@org.osgi.annotation.bundle.Export
@org.osgi.annotation.versioning.Version("0.3.0")
package de.dim.skailify.mcp;
