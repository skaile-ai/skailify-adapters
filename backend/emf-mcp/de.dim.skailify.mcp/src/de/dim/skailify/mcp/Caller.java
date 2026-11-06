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
package de.dim.skailify.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;

/**
 * Who calls a tool: the verified bearer token of the request and what it says. A
 * {@code SkailifyMCPServer} with OAuth on puts one into the transport context of every request;
 * a tool reads it with {@link #of(McpAsyncServerExchange)}. Without OAuth (a static token or
 * loopback only) there is none, and the app runs single-user as before.
 * <p>
 * Upstream gap (eclipse-fennec/emf.osgi-mcp#56): upstream's {@code McpPrincipal} has neither the subject
 * apart from the client nor the raw token; this is what a context extractor there would hand on.
 *
 * @param subject the {@code sub} claim: the user (or, for client credentials, the service account)
 * @param username {@code preferred_username}, or null
 * @param clientId the OAuth client the token was issued to ({@code azp}, {@code client_id}), or null
 * @param issuer the {@code iss} claim, or null
 * @param audience the {@code aud} values
 * @param scopes the granted scopes
 * @param expiresAt when the token expires, or null
 * @param token the raw bearer token, for calls on behalf of the caller; never logged
 */
public record Caller(String subject, String username, String clientId, String issuer, List<String> audience,
		List<String> scopes, Instant expiresAt, String token) {

	/** Key of the caller in the {@link McpTransportContext} and request attribute name */
	public static final String KEY = "de.dim.skailify.mcp.caller";

	public Caller {
		Objects.requireNonNull(subject, "subject");
		audience = audience == null ? List.of() : List.copyOf(audience);
		scopes = scopes == null ? List.of() : List.copyOf(scopes);
	}

	/** The caller of the request the exchange serves, if the endpoint verifies callers */
	public static Optional<Caller> of(McpAsyncServerExchange exchange) {
		if (exchange == null) {
			return Optional.empty();
		}
		McpTransportContext context = exchange.transportContext();
		return context != null && context.get(KEY) instanceof Caller caller ? Optional.of(caller) : Optional.empty();
	}

	/** Whether the token grants the scope; a blank scope is granted to everyone */
	public boolean hasScope(String scope) {
		return scope == null || scope.isBlank() || scopes.contains(scope);
	}

	/** How the caller shows up in a log: the username or subject, and the client */
	public String display() {
		String who = username != null ? username + " (" + subject + ")" : subject;
		return clientId != null && !clientId.equals(subject) ? who + " via " + clientId : who;
	}

	/** Without the token */
	@Override
	public String toString() {
		return "Caller[" + display() + ", scopes=" + scopes + ", expiresAt=" + expiresAt + "]";
	}
}
