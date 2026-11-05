/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.mcp.provider;

import java.lang.reflect.Proxy;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/** A request with the given headers, scheme, host and context path, and nothing else. */
final class Requests {

	private Requests() {
	}

	static HttpServletRequest request(String scheme, String host, String contextPath, Map<String, String> headers) {
		return (HttpServletRequest) Proxy.newProxyInstance(Requests.class.getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> switch (method.getName()) {
				case "getHeader" -> "Host".equalsIgnoreCase((String) args[0]) && !headers.containsKey("Host") ? host
						: headers.get(args[0]);
				case "getHeaders" -> headers.containsKey(args[0])
						? java.util.Collections.enumeration(java.util.List.of(headers.get(args[0])))
						: java.util.Collections.emptyEnumeration();
				case "getHeaderNames" -> java.util.Collections.enumeration(headers.keySet());
				case "getScheme" -> scheme;
				case "getServerName" -> host;
				case "getServerPort" -> -1;
				case "getContextPath" -> contextPath;
				default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
