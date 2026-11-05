/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.mcp.provider;

import static de.dim.skailify.mcp.provider.Requests.request;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.eclipse.fennec.mcp.api.auth.McpPrincipal;
import org.junit.jupiter.api.Test;

import de.dim.skailify.mcp.Caller;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import jakarta.servlet.http.HttpServletRequest;

/** Resource identifier, metadata, challenges and callers, without a servlet container. */
class ResourceServerTest {

	static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();

	@Test
	void derivesTheResourceAndMetadataFromTheForwardedRequestUnderAContextPath() {
		ResourceServer server = new ResourceServer(true, "", "/mcp", "", List.of(), List.of("https://id.example.org/realms/example"),
				List.of("shop:read", "shop:write"), List.of(), "Example Shop");
		HttpServletRequest request = request("http", "127.0.0.1:3000", "/preview/abc",
				Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", "preview.skaile.example, internal"));

		assertThat(server.resource(request)).isEqualTo("https://preview.skaile.example/preview/abc/mcp");
		assertThat(server.metadataUrl(request))
				.isEqualTo("https://preview.skaile.example/preview/abc/.well-known/oauth-protected-resource/mcp");
		assertThat(server.metadata(request))
				.containsEntry("resource", "https://preview.skaile.example/preview/abc/mcp")
				.containsEntry("authorization_servers", List.of("https://id.example.org/realms/example"))
				.containsEntry("scopes_supported", List.of("shop:read", "shop:write"))
				.containsEntry("bearer_methods_supported", List.of("header"))
				.containsEntry("resource_name", "Example Shop");
		// no audience configured: not checked
		assertThat(server.audiences()).isEmpty();
	}

	@Test
	void prefersTheConfiguredUrlsAndAcceptsTheResourceAsAudience() {
		ResourceServer server = new ResourceServer(true, "https://shop.example.org/", "mcp", "https://shop.example.org/mcp",
				List.of("shop-mcp", " "), List.of("https://id.example.org"), List.of(), List.of("shop:read"), null);
		HttpServletRequest request = request("http", "localhost:8090", "", Map.of());

		assertThat(server.base(request)).isEqualTo("https://shop.example.org");
		assertThat(server.metadataUrl(request)).isEqualTo("https://shop.example.org/.well-known/oauth-protected-resource/mcp");
		assertThat(server.audiences()).containsExactly("shop-mcp", "https://shop.example.org/mcp");
		assertThat(server.challengeScopes()).containsExactly("shop:read");
		assertThat(server.metadata(request)).doesNotContainKeys("scopes_supported", "resource_name");
	}

	@Test
	void aConfiguredPublicUrlFixesTheResourceAndItsAudience() {
		ResourceServer server = new ResourceServer(true, "https://preview.skaile.example/preview/abc/", "/mcp", "", List.of(),
				List.of("https://id.example.org"), List.of(), List.of(), null);
		HttpServletRequest request = request("http", "attacker.example", "/preview/abc", Map.of());

		assertThat(server.resource(request)).isEqualTo("https://preview.skaile.example/preview/abc/mcp");
		assertThat(server.audiences()).containsExactly("https://preview.skaile.example/preview/abc/mcp");
	}

	@Test
	void writesRfc6750Challenges() {
		assertThat(BearerFilter.challengeHeader("https://a/.well-known/oauth-protected-resource/mcp", null, "x", List.of()))
				.isEqualTo("Bearer resource_metadata=\"https://a/.well-known/oauth-protected-resource/mcp\"");
		assertThat(BearerFilter.challengeHeader("https://a/m", "insufficient_scope", "The \"write\" scope",
				List.of("shop:read", "shop:write")))
				.isEqualTo("Bearer error=\"insufficient_scope\", error_description=\"The \\\"write\\\" scope\", "
						+ "scope=\"shop:read shop:write\", resource_metadata=\"https://a/m\"");
	}

	@Test
	void readsTheCallerFromTheVerifiedToken() {
		String token = jwt(Map.of("sub", "f3a1", "preferred_username", "alice", "azp", "skaile", "iss", "https://id/realms/example",
				"aud", List.of("shop-mcp", "account"), "scope", "openid shop:read", "exp", 1893456000));
		Caller caller = Callers.of(new McpPrincipal("f3a1", null, List.of("ignored")), token, JSON);

		assertThat(caller.subject()).isEqualTo("f3a1");
		assertThat(caller.username()).isEqualTo("alice");
		assertThat(caller.clientId()).isEqualTo("skaile");
		assertThat(caller.issuer()).isEqualTo("https://id/realms/example");
		assertThat(caller.audience()).containsExactly("shop-mcp", "account");
		assertThat(caller.scopes()).containsExactly("openid", "shop:read");
		assertThat(caller.expiresAt()).isEqualTo(Instant.ofEpochSecond(1893456000));
		assertThat(caller.token()).isEqualTo(token);
		assertThat(caller.hasScope("shop:read")).isTrue();
		assertThat(caller.hasScope("shop:write")).isFalse();
		assertThat(caller.hasScope("")).isTrue();
		assertThat(caller.display()).isEqualTo("alice (f3a1) via skaile");
		assertThat(caller.toString()).doesNotContain(token);
	}

	@Test
	void takesScpArraysAndASingleAudience() {
		Caller caller = Callers.of(McpPrincipal.of("c"), jwt(Map.of("sub", "s", "aud", "https://shop/mcp", "scp", List.of("a", "b"))),
				JSON);
		assertThat(caller.audience()).containsExactly("https://shop/mcp");
		assertThat(caller.scopes()).containsExactly("a", "b");
	}

	@Test
	void fallsBackToThePrincipalForAnOpaqueToken() {
		Caller caller = Callers.of(new McpPrincipal("client-1", null, List.of("shop:read")), "opaque-token", JSON);
		assertThat(caller.subject()).isEqualTo("client-1");
		assertThat(caller.scopes()).containsExactly("shop:read");
		assertThat(caller.audience()).isEmpty();
	}

	static String jwt(Map<String, Object> claims) {
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		try {
			return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
					+ encoder.encodeToString(JSON.writeValueAsBytes(claims)) + ".c2ln";
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
