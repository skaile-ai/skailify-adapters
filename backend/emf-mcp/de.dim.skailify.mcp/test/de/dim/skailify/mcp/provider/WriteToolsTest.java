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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import de.dim.skailify.mcp.AnnotatedMCPTool;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import reactor.core.publisher.Mono;

/** Which tools count as read-only for an endpoint without write.tools */
class WriteToolsTest {

	private static AsyncToolSpecification tool(ToolAnnotations annotations) {
		Tool.Builder definition = Tool.builder("t", Map.of("type", "object"));
		if (annotations != null) {
			definition.annotations(annotations);
		}
		return AsyncToolSpecification.builder().tool(definition.build())
				.callHandler((exchange, request) -> Mono.just(CallToolResult.builder().build())).build();
	}

	@Test
	void aToolThatDeclaresItOnlyReadsIsReadOnly() {
		assertThat(SkailifyMCPServer.readOnly(tool(AnnotatedMCPTool.query("Query")))).isTrue();
	}

	@Test
	void aToolThatChangesDataIsNot() {
		assertThat(SkailifyMCPServer.readOnly(tool(AnnotatedMCPTool.effect("Create", false, false)))).isFalse();
	}

	@Test
	void aToolWithoutAnnotationsOrHintCountsAsAWriteTool() {
		assertThat(SkailifyMCPServer.readOnly(tool(null))).isFalse();
		assertThat(SkailifyMCPServer.readOnly(tool(new ToolAnnotations("Other", null, null, null, null, null)))).isFalse();
	}
}
