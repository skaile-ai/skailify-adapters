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
package de.dim.skailify.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.servlet.runtime.HttpServiceRuntime;
import org.osgi.service.servlet.runtime.HttpServiceRuntimeConstants;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import de.dim.skailify.example.shop.ShopPackage;
import de.dim.skailify.mcp.Caller;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * The MCP endpoint as an OAuth resource server: the upstream JwtTokenVerifier against a local test
 * issuer (its JWKS is served by this test on the same HTTP instance, the tokens are minted here),
 * SkailifyMCPServer under a context path, and the Skailify manifest. No real identity provider is
 * part of this test.
 */
class ResourceServerIntegrationTest {

	private static final String CONTEXT_PATH = "/preview/auth";
	private static final String HTTP_ID = "mcpauthHttp";
	private static final String REPOSITORY_ID = "mcpauth";
	private static final String READ = "shop:read";
	private static final String WRITE = "shop:write";
	private static final String PREVIEW = "X-Skaile-Preview-Authorization";
	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	private static final List<Configuration> CONFIGURATIONS = new ArrayList<>();
	private static final List<ServiceRegistration<?>> REGISTRATIONS = new ArrayList<>();
	private static final List<McpSyncClient> CLIENTS = new ArrayList<>();
	private static BundleContext context;
	private static McpJsonMapper json;
	private static RSAKey key;
	private static String base;
	private static String issuer;
	private static String resource;

	@BeforeAll
	static void start() throws Exception {
		context = FrameworkUtil.getBundle(ResourceServerIntegrationTest.class).getBundleContext();
		ConfigurationAdmin cm = service(ConfigurationAdmin.class, null);
		json = service(McpJsonMapperSupplier.class, null).get();
		configure(cm, "org.apache.felix.http", "mcpauth", Map.of(
				"org.osgi.service.http.port", 0,
				"org.osgi.service.http.host", "127.0.0.1",
				"org.apache.felix.http.context_path", CONTEXT_PATH,
				"org.apache.felix.http.name", "mcpauth",
				"org.apache.felix.http.runtime.init.id", HTTP_ID));
		ServiceReference<HttpServiceRuntime> runtime = reference(HttpServiceRuntime.class, "(id=" + HTTP_ID + ")");
		int port = URI.create(String.valueOf(first(runtime.getProperty(HttpServiceRuntimeConstants.HTTP_SERVICE_ENDPOINT))))
				.getPort();
		base = "http://127.0.0.1:" + port + CONTEXT_PATH;
		issuer = base + "/test-issuer";
		resource = base + "/mcp";

		// the test issuer: a key pair, its public half served as JWKS
		key = new RSAKeyGenerator(2048).keyID("test-key").generate();
		String jwks = new JWKSet(key.toPublicJWK()).toString();
		register(Servlet.class, new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
				response.setContentType("application/json");
				response.getWriter().write(jwks);
			}
		}, Map.of("osgi.http.whiteboard.servlet.pattern", "/test-issuer/jwks", "osgi.http.whiteboard.target",
				"(id=" + HTTP_ID + ")"));
		// a tool that answers who calls it, as a tool sees it
		register(MCPTool.class, new WhoAmI(), Map.of("tool.name", "whoami", "skailify.model", "shop-auth"));

		configure(cm, "fennec.repository.file", "mcpauth", Map.of("repositoryId", REPOSITORY_ID,
				"directory", Files.createTempDirectory("skailify-mcp-auth").toString()));
		configure(cm, "SkailifyEmfTools", "mcpauth", Map.of(
				"skailify.model", "shop-auth",
				"epackage.target", "(emf.nsURI=" + ShopPackage.eNS_URI + ")",
				"repository.target", "(persistence.repository.id=" + REPOSITORY_ID + ")",
				"unique.attributes", new String[] { "Named.name" },
				"scope.read", READ,
				"scope.write", WRITE));
		configure(cm, "SkailifyToolProvider", "mcpauth", Map.of(
				"name", "shop_auth_tools",
				"description", "shop tools under OAuth",
				"tools.target", "(skailify.model=shop-auth)",
				"tools.cardinality.minimum", 7));
		configure(cm, "JwtTokenVerifier", "mcpauth", Map.of(
				"verifier.name", "auth-it",
				"jwks.url", issuer + "/jwks",
				"issuer", issuer));
		configure(cm, "SkailifyMCPServer", "mcpauth", properties(
				"server.name", "shop-auth",
				"osgi.http.whiteboard.servlet.pattern", "/mcp",
				"osgi.http.whiteboard.target", "(id=" + HTTP_ID + ")",
				"has.tool.capability", true,
				"toolProviders.target", "(name=shop_auth_tools)",
				"verifier.target", "(verifier.name=auth-it)",
				"oauth.enabled", "true",
				"oauth.resource", resource,
				"oauth.authorization.servers", new String[] { issuer },
				"oauth.scopes.supported", new String[] { READ, WRITE },
				"oauth.scopes.required", new String[] { READ }));
		configure(cm, "SkailifyManifest", "mcpauth", Map.of(
				"app.id", "shop",
				"app.name", "Example Shop",
				"oauth.enabled", "true",
				"auth.issuers", new String[] { issuer },
				"auth.scopes", new String[] { READ, WRITE },
				"tools.target", "(skailify.model=shop-auth)",
				"actions", new String[] { "{\"name\": \"app.shop.approve_order\", \"approval\": \"optional\"}" },
				"osgi.http.whiteboard.target", "(id=" + HTTP_ID + ")"));
		service(MCPServer.class, "(server.name=shop-auth)");
		awaitStatus(resource, 401);
	}

	@AfterAll
	static void stop() throws Exception {
		for (McpSyncClient client : CLIENTS) {
			client.closeGracefully();
		}
		REGISTRATIONS.forEach(ServiceRegistration::unregister);
		for (int i = CONFIGURATIONS.size() - 1; i >= 0; i--) {
			CONFIGURATIONS.get(i).delete();
		}
	}

	// ------------------------------------------------------------------ discovery

	@Test
	void answersAMissingTokenWithTheResourceMetadata() throws Exception {
		HttpResponse<String> response = post(null);

		assertThat(response.statusCode()).isEqualTo(401);
		String metadataUrl = base + "/.well-known/oauth-protected-resource/mcp";
		assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue(
				"Bearer scope=\"" + READ + "\", resource_metadata=\"" + metadataUrl + "\"");

		// what an MCP client does next: read the metadata and find the authorization server
		HttpResponse<String> metadata = get(metadataUrl);
		assertThat(metadata.statusCode()).isEqualTo(200);
		assertThat(json.readValue(metadata.body(), MAP))
				.containsEntry("resource", resource)
				.containsEntry("authorization_servers", List.of(issuer))
				.containsEntry("scopes_supported", List.of(READ, WRITE))
				.containsEntry("bearer_methods_supported", List.of("header"));
		assertThat(get(base + "/.well-known/oauth-protected-resource").statusCode()).isEqualTo(200);
	}

	@Test
	void refusesTokensThatDoNotFit() throws Exception {
		RSAKey otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
		Map<String, String> refused = new LinkedHashMap<>();
		refused.put("foreign signature", token(otherKey, "alice", resource, Instant.now().plusSeconds(300), READ));
		refused.put("expired", token(key, "alice", resource, Instant.now().minusSeconds(300), READ));
		refused.put("other audience", token(key, "alice", "https://other.example.org/mcp", Instant.now().plusSeconds(300), READ));
		for (Map.Entry<String, String> entry : refused.entrySet()) {
			HttpResponse<String> response = post(entry.getValue());
			assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(401);
			assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).as(entry.getKey())
					.startsWith("Bearer error=\"invalid_token\"")
					.contains("resource_metadata=\"" + base + "/.well-known/oauth-protected-resource/mcp\"");
		}
	}

	@Test
	void refusesATokenWithoutTheRequiredScope() throws Exception {
		HttpResponse<String> response = post(token(key, "alice", resource, Instant.now().plusSeconds(300), WRITE));

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow())
				.startsWith("Bearer error=\"insufficient_scope\"")
				.contains("scope=\"" + READ + "\"");
		assertThat(json.readValue(response.body(), MAP)).containsEntry("error", "insufficient_scope");
	}

	// ------------------------------------------------------------------ behind the Skaile preview proxy

	@Test
	void takesTheTokenFromThePreviewHeaderWithoutAuthorization() throws Exception {
		String token = token(key, "frank", resource, Instant.now().plusSeconds(300), READ);
		assertThat(post(null, "Bearer " + token).statusCode()).isEqualTo(200);

		// the whole MCP session through the header, the caller as the tools see it
		McpSyncClient client = connect(HttpRequest.newBuilder().header(PREVIEW, "Bearer " + token));
		assertThat(structured(client.callTool(new CallToolRequest("whoami", Map.of()))))
				.containsEntry("subject", "frank").containsEntry("tokenMatches", token.hashCode());
	}

	@Test
	void aRealAuthorizationWinsOverThePreviewHeader() throws Exception {
		String good = token(key, "grace", resource, Instant.now().plusSeconds(300), READ);
		String expired = token(key, "grace", resource, Instant.now().minusSeconds(300), READ);

		assertThat(post(good, "Bearer " + expired).statusCode()).isEqualTo(200);
		HttpResponse<String> refused = post(expired, "Bearer " + good);
		assertThat(refused.statusCode()).isEqualTo(401);
		assertThat(refused.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer error=\"invalid_token\"");
	}

	@Test
	void refusesABadTokenInThePreviewHeader() throws Exception {
		RSAKey otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
		HttpResponse<String> foreign = post(null, "Bearer " + token(otherKey, "mallory", resource, Instant.now().plusSeconds(300), READ));
		assertThat(foreign.statusCode()).isEqualTo(401);
		assertThat(foreign.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer error=\"invalid_token\"");
		assertThat(post(null, "Basic bWFsbG9yeTpwdw==").statusCode()).isEqualTo(401);
		assertThat(post(null, "").statusCode()).isEqualTo(401);
	}

	// ------------------------------------------------------------------ the caller in the tools

	@Test
	void handsTheVerifiedCallerToTheTools() throws Exception {
		String token = token(key, "alice", resource, Instant.now().plusSeconds(300), READ + " " + WRITE);
		McpSyncClient client = connect(token);

		Map<String, Object> me = structured(client.callTool(new CallToolRequest("whoami", Map.of())));
		assertThat(me)
				.containsEntry("subject", "alice")
				.containsEntry("username", "alice@example.org")
				.containsEntry("clientId", "skaile")
				.containsEntry("issuer", issuer)
				.containsEntry("scopes", List.of(READ, WRITE))
				.containsEntry("hasToken", true)
				.containsEntry("tokenMatches", token.hashCode());

		CallToolResult created = client.callTool(new CallToolRequest("create",
				Map.of("type", "Customer", "fields", Map.of("name", "Elektro Schmidt"))));
		assertThat(created.isError()).as(String.valueOf(created.structuredContent())).isFalse();
		CallToolResult found = client.callTool(new CallToolRequest("query",
				Map.of("type", "Customer", "filter", Map.of("name", "Elektro Schmidt"))));
		assertThat(structured(found)).containsEntry("total", 1);
	}

	@Test
	void eachRequestCarriesItsOwnCaller() throws Exception {
		McpSyncClient bob = connect(token(key, "bob", resource, Instant.now().plusSeconds(300), READ));
		McpSyncClient carol = connect(token(key, "carol", resource, Instant.now().plusSeconds(300), READ));

		assertThat(structured(bob.callTool(new CallToolRequest("whoami", Map.of())))).containsEntry("subject", "bob");
		assertThat(structured(carol.callTool(new CallToolRequest("whoami", Map.of())))).containsEntry("subject", "carol");
		assertThat(structured(bob.callTool(new CallToolRequest("whoami", Map.of())))).containsEntry("subject", "bob");
	}

	@Test
	void aReadTokenReadsButDoesNotWrite() throws Exception {
		McpSyncClient reader = connect(token(key, "dave", resource, Instant.now().plusSeconds(300), READ));

		CallToolResult query = reader.callTool(new CallToolRequest("query", Map.of("type", "Customer")));
		assertThat(query.isError()).isFalse();
		CallToolResult create = reader.callTool(new CallToolRequest("create",
				Map.of("type", "Customer", "fields", Map.of("name", "Not allowed Ltd"))));
		assertThat(create.isError()).isTrue();
		assertThat(structured(create)).containsEntry("error", "insufficient_scope").containsEntry("scope", WRITE);
	}

	// ------------------------------------------------------------------ manifest

	@Test
	@SuppressWarnings("unchecked")
	void servesTheManifestWithItsGrantCatalogue() throws Exception {
		HttpResponse<String> response = get(base + "/.well-known/skailify.json");
		assertThat(response.statusCode()).isEqualTo(200);
		Map<String, Object> manifest = json.readValue(response.body(), MAP);

		assertThat(manifest).containsEntry("skailify", "0.1-draft");
		assertThat((Map<String, Object>) manifest.get("app")).containsEntry("id", "shop")
				.containsEntry("embedUrl", base + "/");
		assertThat((Map<String, Object>) manifest.get("mcp")).containsEntry("url", resource);
		assertThat((Map<String, Object>) manifest.get("auth")).containsEntry("type", "oauth2")
				.containsEntry("issuers", List.of(issuer))
				.containsEntry("protectedResourceMetadata", base + "/.well-known/oauth-protected-resource/mcp");

		List<Map<String, Object>> grants = (List<Map<String, Object>>) manifest.get("grants");
		assertThat(grants).extracting(g -> g.get("name")).containsExactly("create", "delete", "describe_model", "get",
				"query", "update", "whoami", "app.shop.approve_order");
		Map<String, Object> query = grants.get(4);
		assertThat(query).containsEntry("kind", "query").containsEntry("effectClass", "routine")
				.containsEntry("approval", "none").containsEntry("scope", READ);
		Map<String, Object> update = grants.get(5);
		assertThat(update).containsEntry("kind", "effect").containsEntry("effectClass", "routine")
				.containsEntry("approval", "required").containsEntry("scope", WRITE);
		List<Map<String, Object>> types = (List<Map<String, Object>>) update.get("types");
		assertThat(types).contains(
				Map.of("type", "Order", "grant", "shop.orders", "effectClass", "routine", "approval", "required"),
				Map.of("type", "Product", "grant", "shop.catalog", "effectClass", "privileged", "approval", "required"));
		assertThat(types).extracting(t -> t.get("type")).doesNotContain("PaymentAccount", "Customer");
		assertThat(grants.get(7)).containsEntry("channel", "app").containsEntry("approval", "optional");
	}

	// ------------------------------------------------------------------ helpers

	/** Answers the caller as the tool sees it; the token only as hash */
	private static final class WhoAmI implements MCPTool {

		@Override
		public String getName() {
			return "whoami";
		}

		@Override
		public String getDescription() {
			return "Who calls";
		}

		@Override
		public String getInputSchema() {
			return "{\"type\":\"object\",\"properties\":{}}";
		}

		@Override
		public String getOutputSchema() {
			return null;
		}

		@Override
		public Mono<CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
			Map<String, Object> me = new LinkedHashMap<>();
			Caller.of(exchange).ifPresent(caller -> {
				me.put("subject", caller.subject());
				me.put("username", caller.username());
				me.put("clientId", caller.clientId());
				me.put("issuer", caller.issuer());
				me.put("scopes", caller.scopes());
				me.put("hasToken", caller.token() != null);
				me.put("tokenMatches", caller.token() == null ? 0 : caller.token().hashCode());
			});
			return Mono.just(CallToolResult.builder().structuredContent(me).addTextContent(me.toString()).build());
		}
	}

	private static String token(RSAKey signer, String subject, String audience, Instant expiry, String scope)
			throws JOSEException {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(issuer)
				.subject(subject)
				.audience(List.of(audience, "account"))
				.expirationTime(Date.from(expiry))
				.issueTime(Date.from(Instant.now().minusSeconds(5)))
				.claim("azp", "skaile")
				.claim("preferred_username", subject + "@example.org")
				.claim("scope", scope)
				.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), claims);
		jwt.sign(new RSASSASigner(signer));
		return jwt.serialize();
	}

	private static McpSyncClient connect(String token) throws Exception {
		return connect(HttpRequest.newBuilder().header("Authorization", "Bearer " + token));
	}

	private static McpSyncClient connect(HttpRequest.Builder requests) throws Exception {
		URI uri = URI.create(resource);
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
				.builder(uri.getScheme() + "://" + uri.getAuthority())
				.endpoint(uri.getPath())
				.requestBuilder(requests)
				.jsonMapper(json)
				.build();
		McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
				.initializationTimeout(Duration.ofSeconds(30)).build();
		client.initialize();
		CLIENTS.add(client);
		return client;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> structured(CallToolResult result) {
		assertThat(result.structuredContent()).isInstanceOf(Map.class);
		return (Map<String, Object>) result.structuredContent();
	}

	private static HttpResponse<String> post(String token) throws Exception {
		return post(token, null);
	}

	/** An initialize request with {@code Authorization: Bearer <token>} and the preview header as given, each when not null */
	private static HttpResponse<String> post(String token, String preview) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(resource))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
						+ "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}"));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (preview != null) {
			request.header(PREVIEW, preview);
		}
		return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static HttpResponse<String> get(String url) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	/** Until the endpoint and its filters are registered */
	private static void awaitStatus(String url, int status) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (post(null).statusCode() != status) {
			assertThat(System.currentTimeMillis()).as("no %s from %s", status, url).isLessThan(deadline);
			Thread.sleep(200);
		}
	}

	private static Map<String, Object> properties(Object... keysAndValues) {
		Map<String, Object> properties = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			properties.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return properties;
	}

	private static <S> void register(Class<S> type, S service, Map<String, Object> properties) {
		REGISTRATIONS.add(context.registerService(type, service, new Hashtable<>(properties)));
	}

	private static void configure(ConfigurationAdmin cm, String factoryPid, String name, Map<String, Object> properties)
			throws Exception {
		Configuration configuration = cm.getFactoryConfiguration(factoryPid, name, "?");
		CONFIGURATIONS.add(configuration);
		configuration.update(new Hashtable<>(properties));
	}

	private static <S> S service(Class<S> type, String filter) throws Exception {
		ServiceReference<S> reference = reference(type, filter);
		S service = context.getService(reference);
		assertThat(service).as("%s %s", type.getSimpleName(), filter).isNotNull();
		return service;
	}

	private static <S> ServiceReference<S> reference(Class<S> type, String filter) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			Collection<ServiceReference<S>> references = context.getServiceReferences(type, filter);
			if (!references.isEmpty()) {
				return references.iterator().next();
			}
			assertThat(System.currentTimeMillis()).as("no %s %s", type.getSimpleName(), filter).isLessThan(deadline);
			Thread.sleep(100);
		}
	}

	private static Object first(Object value) {
		if (value instanceof String[] array) {
			return array[0];
		}
		if (value instanceof Collection<?> collection) {
			return collection.iterator().next();
		}
		return value;
	}
}
