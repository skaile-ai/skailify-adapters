/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.mcp.provider;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.eclipse.fennec.mcp.api.auth.McpPrincipal;

import de.dim.skailify.mcp.Caller;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

/**
 * Builds the {@link Caller} of a token the verifier has accepted. The verifier answers an
 * upstream {@link McpPrincipal} (client, expiry, scopes); what else the app needs (the user, the
 * audience) is read from the claims of the same, already verified JWT. A token that is no JWT
 * (an opaque token a verifier introspected) yields a caller from the principal alone, without
 * audience.
 */
final class Callers {

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	private Callers() {
	}

	static Caller of(McpPrincipal principal, String token, McpJsonMapper json) {
		Map<String, Object> claims = claims(token, json);
		if (claims == null) {
			return new Caller(principal.clientId(), null, null, null, List.of(), principal.scopes(), principal.expiresAt(),
					token);
		}
		String subject = string(claims.get("sub"));
		String clientId = string(claims.get("azp"));
		if (clientId == null) {
			clientId = string(claims.get("client_id"));
		}
		List<String> scopes = strings(claims.get("scope"));
		if (scopes.isEmpty()) {
			scopes = strings(claims.get("scp"));
		}
		if (scopes.isEmpty()) {
			scopes = principal.scopes();
		}
		Instant expiresAt = claims.get("exp") instanceof Number exp ? Instant.ofEpochSecond(exp.longValue())
				: principal.expiresAt();
		return new Caller(subject != null ? subject : principal.clientId(), string(claims.get("preferred_username")),
				clientId, string(claims.get("iss")), strings(claims.get("aud")), scopes, expiresAt, token);
	}

	/** The claims of a JWT, without checking anything; null if it is none */
	static Map<String, Object> claims(String token, McpJsonMapper json) {
		String[] parts = token == null ? new String[0] : token.split("\\.");
		if (parts.length != 3) {
			return null;
		}
		try {
			return json.readValue(Base64.getUrlDecoder().decode(parts[1]), MAP);
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}

	private static String string(Object value) {
		return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
	}

	/** A space-separated string or an array */
	private static List<String> strings(Object value) {
		List<String> result = new ArrayList<>();
		if (value instanceof Collection<?> values) {
			values.stream().map(Callers::string).filter(v -> v != null).forEach(result::add);
		} else if (value != null) {
			for (String part : String.valueOf(value).strip().split("\\s+")) {
				if (!part.isEmpty()) {
					result.add(part);
				}
			}
		}
		return result;
	}
}
