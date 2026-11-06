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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.fennec.mcp.api.auth.McpPrincipal;
import org.eclipse.fennec.mcp.api.auth.McpTokenVerifier;

import de.dim.skailify.mcp.Caller;
import io.modelcontextprotocol.json.McpJsonMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The OAuth resource server gate in front of the MCP endpoint (RFC 6750, RFC 9728, MCP
 * authorization). With OAuth on, a request needs a bearer token that
 * <ul>
 * <li>the {@link McpTokenVerifier} accepts (signature, issuer, expiry), else 401
 * {@code invalid_token};</li>
 * <li>carries one of the accepted audiences, else 401 {@code invalid_token};</li>
 * <li>grants the required scopes, else 403 {@code insufficient_scope}.</li>
 * </ul>
 * Without a token the answer is a 401 whose {@code WWW-Authenticate} names the protected resource
 * metadata, which is how an MCP client finds the authorization server. With no verifier bound the
 * endpoint is closed (503), never open. The verified {@link Caller} goes on the request for the
 * transport's context extractor, the upstream {@link McpPrincipal} as well.
 * <p>
 * With OAuth off the filter lets everything through to the upstream {@code McpAuthenticationFilter}
 * behind it (static token or loopback only).
 * <p>
 * In both modes a request without {@code Authorization} but with the Skaile preview proxy's
 * {@code X-Skaile-Preview-Authorization} goes on as if that were its {@code Authorization}
 * ({@link PreviewAuthorization}), so the app's own token reaches it through a preview URL.
 * <p>
 * Upstream gap (eclipse-fennec/emf.osgi-mcp#57): upstream answers a bare 401 and does not check the audience
 * against the resource.
 */
final class BearerFilter implements Filter {

	private static final Logger LOG = Logger.getLogger(BearerFilter.class.getName());
	private static final String BEARER = "Bearer ";

	private final ResourceServer server;
	private final Supplier<McpTokenVerifier> verifier;
	private final McpJsonMapper json;

	BearerFilter(ResourceServer server, Supplier<McpTokenVerifier> verifier, McpJsonMapper json) {
		this.server = server;
		this.verifier = verifier;
		this.json = json;
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
		if (!(req instanceof HttpServletRequest original) || !(res instanceof HttpServletResponse response)) {
			chain.doFilter(req, res);
			return;
		}
		HttpServletRequest request = PreviewAuthorization.of(original);
		if (!server.enabled) {
			chain.doFilter(request, response);
			return;
		}
		String token = bearerToken(request);
		if (token == null) {
			// no authentication information: no error code (RFC 6750 3.1)
			challenge(request, response, HttpServletResponse.SC_UNAUTHORIZED, null, "A bearer token is required",
					server.challengeScopes());
			return;
		}
		McpTokenVerifier current = verifier.get();
		if (current == null) {
			LOG.warning("OAuth is on but no token verifier is bound; refusing the request");
			send(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "temporarily_unavailable",
					"The endpoint cannot verify tokens at the moment");
			return;
		}
		Optional<McpPrincipal> principal;
		try {
			principal = current.verify(token, request);
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Token verifier failed; rejecting the request", e);
			principal = Optional.empty();
		}
		if (principal == null || principal.isEmpty()) {
			challenge(request, response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token",
					"The token is invalid or expired", List.of());
			return;
		}
		Caller caller = Callers.of(principal.get(), token, json);
		if (!server.audiences().isEmpty() && caller.audience().stream().noneMatch(server.audiences()::contains)) {
			LOG.fine(() -> "Token of " + caller.display() + " is for " + caller.audience() + ", not " + server.audiences());
			challenge(request, response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token",
					"The token is not meant for this resource", List.of());
			return;
		}
		List<String> missing = new ArrayList<>(server.scopesRequired);
		missing.removeAll(caller.scopes());
		if (!missing.isEmpty()) {
			challenge(request, response, HttpServletResponse.SC_FORBIDDEN, "insufficient_scope",
					"The token lacks the scopes " + missing, server.scopesRequired);
			return;
		}
		request.setAttribute(Caller.KEY, caller);
		request.setAttribute(McpTokenVerifier.PRINCIPAL_ATTRIBUTE, principal.get());
		chain.doFilter(request, response);
	}

	/** The {@code WWW-Authenticate} challenge and a JSON body saying the same */
	private void challenge(HttpServletRequest request, HttpServletResponse response, int status, String error,
			String description, List<String> scopes) throws IOException {
		response.setHeader("WWW-Authenticate", challengeHeader(server.metadataUrl(request), error, description, scopes));
		send(response, status, error == null ? "unauthorized" : error, description);
	}

	static String challengeHeader(String metadataUrl, String error, String description, List<String> scopes) {
		StringBuilder header = new StringBuilder("Bearer ");
		List<String> params = new ArrayList<>();
		if (error != null) {
			params.add("error=" + quote(error));
			params.add("error_description=" + quote(description));
		}
		if (scopes != null && !scopes.isEmpty()) {
			params.add("scope=" + quote(String.join(" ", scopes)));
		}
		params.add("resource_metadata=" + quote(metadataUrl));
		return header.append(String.join(", ", params)).toString();
	}

	private static String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private void send(HttpServletResponse response, int status, String error, String description) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		body.put("error_description", description);
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write(json.writeValueAsString(body));
	}

	private static String bearerToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || header.length() <= BEARER.length() || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			return null;
		}
		String token = header.substring(BEARER.length()).strip();
		return token.isEmpty() ? null : token;
	}
}
