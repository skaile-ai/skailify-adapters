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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * The app's bearer token behind the Skaile preview proxy (skaile-ai/platform#9264,
 * docs/preview-contract.md): the proxy never forwards {@code Authorization} to a preview, so a client
 * sends the app-issued token as {@code X-Skaile-Preview-Authorization: Bearer <token>}, which the proxy
 * forwards unchanged (and drops when it carries a Skaile token). Here it stands in for a missing
 * {@code Authorization}, for everything behind: this filter and upstream's McpAuthenticationFilter, in
 * OAuth and in static-token mode. A real {@code Authorization} header always wins.
 */
final class PreviewAuthorization extends HttpServletRequestWrapper {

	static final String HEADER = "X-Skaile-Preview-Authorization";
	private static final String AUTHORIZATION = "Authorization";

	private final String value;

	private PreviewAuthorization(HttpServletRequest request, String value) {
		super(request);
		this.value = value;
	}

	/** The request as is when it has an {@code Authorization} header or no preview header, else wrapped */
	static HttpServletRequest of(HttpServletRequest request) {
		if (request.getHeader(AUTHORIZATION) != null) {
			return request;
		}
		String preview = request.getHeader(HEADER);
		return preview == null || preview.isBlank() ? request : new PreviewAuthorization(request, preview.strip());
	}

	@Override
	public String getHeader(String name) {
		return AUTHORIZATION.equalsIgnoreCase(name) ? value : super.getHeader(name);
	}

	@Override
	public Enumeration<String> getHeaders(String name) {
		return AUTHORIZATION.equalsIgnoreCase(name) ? Collections.enumeration(List.of(value)) : super.getHeaders(name);
	}

	@Override
	public Enumeration<String> getHeaderNames() {
		List<String> names = new ArrayList<>();
		Enumeration<String> own = super.getHeaderNames();
		while (own != null && own.hasMoreElements()) {
			names.add(own.nextElement());
		}
		names.add(AUTHORIZATION);
		return Collections.enumeration(names);
	}
}
