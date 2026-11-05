/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.mcp;

import org.eclipse.fennec.mcp.api.MCPTool;
import org.osgi.annotation.versioning.ConsumerType;

import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * An MCP tool with a human-readable title and the MCP tool annotations. Register it as an
 * {@link MCPTool} service; a {@code SkailifyToolProvider} serves the annotations, the upstream
 * {@code MCPToolProvider} ignores them.
 * <p>
 * Upstream gap: meant to become part of {@link MCPTool}.
 */
@ConsumerType
public interface AnnotatedMCPTool extends MCPTool {

	/** The title shown to people, or null */
	default String getTitle() {
		return null;
	}

	/**
	 * The tool annotations ({@code readOnlyHint}, {@code destructiveHint},
	 * {@code idempotentHint}, {@code openWorldHint}), or null for none. The SDK's
	 * {@link ToolAnnotations} title is filled from {@link #getTitle()} when it has none.
	 */
	default ToolAnnotations getAnnotations() {
		return null;
	}

	/** Annotations of a tool that only reads */
	static ToolAnnotations query(String title) {
		return new ToolAnnotations(title, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, null);
	}

	/**
	 * Annotations of a tool that changes data
	 *
	 * @param destructive whether it may destroy or overwrite data (delete, update) rather than
	 *            only add
	 * @param idempotent whether calling it again with the same arguments has no further effect
	 */
	static ToolAnnotations effect(String title, boolean destructive, boolean idempotent) {
		return new ToolAnnotations(title, Boolean.FALSE, destructive, idempotent, Boolean.FALSE, null);
	}
}
