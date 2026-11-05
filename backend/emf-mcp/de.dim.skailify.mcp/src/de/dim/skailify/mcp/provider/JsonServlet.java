/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.mcp.provider;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

import io.modelcontextprotocol.json.McpJsonMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A public, read-only JSON document computed per request (the URLs in it depend on how the app
 * is reached): the protected resource metadata, the Skailify manifest. Readable cross-origin, as
 * discovery documents are.
 */
final class JsonServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private final transient Function<HttpServletRequest, Map<String, Object>> document;
	private final transient McpJsonMapper json;

	JsonServlet(Function<HttpServletRequest, Map<String, Object>> document, McpJsonMapper json) {
		this.document = document;
		this.json = json;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		byte[] body = json.writeValueAsBytes(document.apply(request));
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("application/json");
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Cache-Control", "max-age=300");
		response.setContentLength(body.length);
		response.getOutputStream().write(body);
	}

	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response) {
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
		response.setHeader("Access-Control-Allow-Headers", "*");
		response.setStatus(HttpServletResponse.SC_NO_CONTENT);
	}
}
