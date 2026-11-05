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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.fennec.mcp.api.MCPServer;
import org.eclipse.fennec.persistence.repository.api.Repository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceObjects;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.servlet.runtime.HttpServiceRuntime;
import org.osgi.service.servlet.runtime.HttpServiceRuntimeConstants;
import org.osgi.util.tracker.ServiceTracker;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import de.dim.skailify.example.shop.Customer;
import de.dim.skailify.example.shop.Order;
import de.dim.skailify.example.shop.OrderStatus;
import de.dim.skailify.example.shop.ShopFactory;
import de.dim.skailify.example.shop.ShopPackage;

/**
 * The example shop model over MCP: the generic model tools on its EPackage and a file repository,
 * behind SkailifyMCPServer (OAuth off) at /mcp of an HTTP instance that runs under a context path,
 * as in a Skaile preview; the read tools alone at /mcp-read, and behind a static token at
 * /mcp-token. Driven by the MCP SDK client over real HTTP.
 */
class ModelToolsIntegrationTest {

	private static final String CONTEXT_PATH = "/preview/it";
	private static final String HTTP_ID = "mcpitHttp";
	private static final String REPOSITORY_ID = "mcpit";
	/** The static token of the /mcp-token endpoint */
	private static final String STATIC_TOKEN = "it-static-token";
	private static final String PREVIEW = "X-Skaile-Preview-Authorization";

	private static final List<Configuration> CONFIGURATIONS = new ArrayList<>();
	private static BundleContext context;
	private static Path directory;
	private static ServiceTracker<Repository, Repository> repositories;
	private static McpSyncClient client;
	private static Customer customer;
	private static int port;

	@BeforeAll
	static void startServer() throws Exception {
		context = FrameworkUtil.getBundle(ModelToolsIntegrationTest.class).getBundleContext();
		ConfigurationAdmin cm = service(ConfigurationAdmin.class, null);
		directory = Files.createTempDirectory("skailify-mcp-it");

		configure(cm, "org.apache.felix.http", "mcpit", Map.of(
				"org.osgi.service.http.port", 0,
				"org.osgi.service.http.host", "127.0.0.1",
				"org.apache.felix.http.context_path", CONTEXT_PATH,
				"org.apache.felix.http.name", "mcpit",
				"org.apache.felix.http.runtime.init.id", HTTP_ID));
		configure(cm, "fennec.repository.file", "mcpit", Map.of("repositoryId", REPOSITORY_ID, "directory", directory.toString()));
		configure(cm, "SkailifyEmfTools", "mcpit", Map.of(
				"skailify.model", "shop-it",
				"epackage.target", "(emf.nsURI=" + ShopPackage.eNS_URI + ")",
				"repository.target", "(persistence.repository.id=" + REPOSITORY_ID + ")",
				"unique.attributes", new String[] { "Named.name" }));
		configure(cm, "SkailifyToolProvider", "mcpit", Map.of(
				"name", "shop_it_tools",
				"description", "shop tools under test",
				"tools.target", "(skailify.model=shop-it)",
				"tools.cardinality.minimum", 6));
		configure(cm, "SkailifyMCPServer", "mcpit", Map.of(
				"server.name", "shop-it",
				"osgi.http.whiteboard.servlet.pattern", "/mcp",
				"osgi.http.whiteboard.target", "(id=" + HTTP_ID + ")",
				"has.tool.capability", true,
				"toolProviders.target", "(name=shop_it_tools)",
				"toolProviders.cardinality.minimum", 1));
		// the same tools, reads only, on an endpoint of their own
		configure(cm, "SkailifyToolProvider", "mcpitread", Map.of(
				"name", "shop_it_read_tools",
				"description", "shop read tools under test",
				"tools.target", "(&(skailify.model=shop-it)(tool.readOnly=true))",
				"tools.cardinality.minimum", 3));
		configure(cm, "SkailifyMCPServer", "mcpitread", Map.of(
				"server.name", "shop-it-read",
				"osgi.http.whiteboard.servlet.pattern", "/mcp-read",
				"osgi.http.whiteboard.target", "(id=" + HTTP_ID + ")",
				"has.tool.capability", true,
				"toolProviders.target", "(name=shop_it_read_tools)",
				"toolProviders.cardinality.minimum", 1));
		// the read tools once more, behind a static token instead of loopback-only
		configure(cm, "SkailifyMCPServer", "mcpittoken", Map.of(
				"server.name", "shop-it-token",
				"osgi.http.whiteboard.servlet.pattern", "/mcp-token",
				"osgi.http.whiteboard.target", "(id=" + HTTP_ID + ")",
				"has.tool.capability", true,
				"toolProviders.target", "(name=shop_it_read_tools)",
				"toolProviders.cardinality.minimum", 1,
				"auth.token", STATIC_TOKEN));

		repositories = new ServiceTracker<>(context, context.createFilter(
				"(&(objectClass=" + Repository.class.getName() + ")(persistence.repository.id=" + REPOSITORY_ID + "))"), null);
		repositories.open();
		assertThat(repositories.waitForService(TimeUnit.SECONDS.toMillis(20))).as("repository").isNotNull();
		seed();

		service(MCPServer.class, "(server.name=shop-it)");
		service(MCPServer.class, "(server.name=shop-it-read)");
		service(MCPServer.class, "(server.name=shop-it-token)");
		ServiceReference<HttpServiceRuntime> runtime = reference(HttpServiceRuntime.class, "(id=" + HTTP_ID + ")");
		port = URI.create(String.valueOf(first(runtime.getProperty(HttpServiceRuntimeConstants.HTTP_SERVICE_ENDPOINT))))
				.getPort();
		client = connect("/mcp");
	}

	private static McpSyncClient connect(String endpoint) throws Exception {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
				.endpoint(CONTEXT_PATH + endpoint)
				.jsonMapper(service(McpJsonMapperSupplier.class, null).get())
				.build();
		McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
				.initializationTimeout(Duration.ofSeconds(30)).build();
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			try {
				client.initialize();
				break;
			} catch (RuntimeException e) {
				// the servlet may not be registered yet
				if (System.currentTimeMillis() > deadline) {
					throw e;
				}
				Thread.sleep(250);
			}
		}
		return client;
	}

	@AfterAll
	static void stopServer() throws Exception {
		if (client != null) {
			client.closeGracefully();
		}
		if (repositories != null) {
			repositories.close();
		}
		for (int i = CONFIGURATIONS.size() - 1; i >= 0; i--) {
			CONFIGURATIONS.get(i).delete();
		}
	}

	/** An open order and a draft of one customer */
	private static void seed() throws Exception {
		withRepository(repository -> {
			customer = ShopFactory.eINSTANCE.createCustomer();
			customer.setName("Müller Tiefbau GmbH");
			customer.setEmail("orders@mueller.example.org");
			Order open = ShopFactory.eINSTANCE.createOrder();
			open.setCustomer(customer);
			open.setNumber("2026-17");
			open.setStatus(OrderStatus.OPEN);
			open.setTotal(new BigDecimal("4165.00"));
			open.setInternalNote("not for agents");
			Order draft = ShopFactory.eINSTANCE.createOrder();
			draft.setCustomer(customer);
			draft.setNumber("2026-18");
			draft.setStatus(OrderStatus.DRAFT);
			repository.saveAll(List.of(customer, open, draft));
		});
	}

	@Test
	void listsTheModelToolsWithTheirAnnotations() {
		List<Tool> tools = client.listTools().tools();

		assertThat(tools).extracting(Tool::name)
				.containsExactlyInAnyOrder("describe_model", "query", "get", "create", "update", "delete");
		Tool query = tools.stream().filter(t -> t.name().equals("query")).findFirst().orElseThrow();
		assertThat(query.annotations().readOnlyHint()).isTrue();
		assertThat(query.title()).isNotBlank();
		for (String name : List.of("describe_model", "query", "get")) {
			Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
			assertThat(tool.annotations().readOnlyHint()).as(name).isTrue();
		}
		// every change tool says so; create only adds, update and delete overwrite or remove
		for (String name : List.of("create", "update", "delete")) {
			Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
			assertThat(tool.annotations().readOnlyHint()).as(name).isFalse();
			assertThat(tool.annotations().destructiveHint()).as(name).isEqualTo(!name.equals("create"));
		}
	}

	@Test
	void servesTheReadToolsOnTheirOwn() throws Exception {
		McpSyncClient reader = connect("/mcp-read");
		try {
			assertThat(reader.listTools().tools()).extracting(Tool::name)
					.containsExactlyInAnyOrder("describe_model", "query", "get");
			CallToolResult result = reader.callTool(new CallToolRequest("query",
					Map.of("type", "Order", "filter", Map.of("number", "2026-17"))));
			assertThat(result.isError()).isFalse();
			assertThat(list(structured(result).get("items"))).singleElement()
					.satisfies(item -> assertThat(item).containsEntry("number", "2026-17"));
		} finally {
			reader.closeGracefully();
		}
	}

	@Test
	void describesTheModelWithItsConstraints() {
		CallToolResult result = call("describe_model", Map.of("type", "Order"));

		assertThat(result.isError()).isFalse();
		Map<String, Object> order = structured(result);
		assertThat(order).containsEntry("name", "Order").containsEntry("stored", true).containsKey("schema");
		assertThat(String.valueOf(order.get("constraints"))).contains("totalRequired", "OrderStatus::DRAFT");
		assertThat(String.valueOf(order.get("documentation"))).startsWith("An order of a customer. Save it as DRAFT");
	}

	@Test
	@SuppressWarnings("unchecked")
	void hidesWhatTheModelHides() {
		Map<String, Object> model = structured(call("describe_model", Map.of()));

		List<Map<String, Object>> classes = (List<Map<String, Object>>) model.get("classes");
		assertThat(classes).extracting(c -> c.get("name")).contains("Customer", "Order", "Product")
				.doesNotContain("PaymentAccount");
		Map<String, Object> order = classes.stream().filter(c -> "Order".equals(c.get("name"))).findFirst().orElseThrow();
		assertThat((List<Map<String, Object>>) order.get("attributes")).extracting(a -> a.get("name"))
				.contains("number", "total").doesNotContain("internalNote");
		Map<String, Object> customerClass = classes.stream().filter(c -> "Customer".equals(c.get("name"))).findFirst().orElseThrow();
		assertThat((List<Map<String, Object>>) customerClass.get("references")).extracting(r -> r.get("name"))
				.doesNotContain("account");

		Map<String, Object> found = structured(call("query", Map.of("type", "Order", "filter", Map.of("number", "2026-17"))));
		assertThat(list(found.get("items"))).singleElement().satisfies(item -> assertThat(item).doesNotContainKey("internalNote"));
		assertThat(call("query", Map.of("type", "PaymentAccount")).isError()).isTrue();
	}

	@Test
	void queriesTheOpenOrdersOfACustomer() {
		CallToolResult result = call("query", Map.of("type", "Order",
				"filter", Map.of("status", List.of("OPEN", "SHIPPED"), "customer", customer.getId())));

		assertThat(result.isError()).isFalse();
		Map<String, Object> answer = structured(result);
		assertThat(answer).containsEntry("total", 1);
		List<Map<String, Object>> items = list(answer.get("items"));
		assertThat(items).singleElement().satisfies(item -> {
			assertThat(item).containsEntry("number", "2026-17").containsEntry("status", "OPEN");
			assertThat(item.get("customer")).isEqualTo(Map.of("type", "Customer", "id", customer.getId(), "label", "Müller Tiefbau GmbH"));
		});

		Map<String, Object> byCustomer = structured(call("query", Map.of("type", "Order",
				"filter", Map.of("customer.name", Map.of("contains", "müller")), "orderBy", "number")));
		assertThat(list(byCustomer.get("items"))).extracting(item -> item.get("number")).containsExactly("2026-17", "2026-18");
	}

	@Test
	void createsACustomerOnceByName() throws Exception {
		CallToolResult created = call("create", Map.of("type", "Customer",
				"fields", Map.of("name", "Elektro Schmidt", "email", "info@schmidt.example.org")));

		assertThat(created.isError()).isFalse();
		String id = String.valueOf(structured(created).get("id"));
		withRepository(repository -> {
			Customer stored = (Customer) repository.getEObject(ShopPackage.Literals.CUSTOMER, id);
			assertThat(stored.getName()).isEqualTo("Elektro Schmidt");
			assertThat(stored.getEmail()).isEqualTo("info@schmidt.example.org");
		});

		CallToolResult again = call("create", Map.of("type", "Customer", "fields", Map.of("name", " elektro SCHMIDT ")));
		assertThat(again.isError()).isTrue();
		assertThat(structured(again)).containsEntry("error", "conflict");
	}

	@Test
	void refusesAnOrderThatViolatesAnOclConstraint() throws Exception {
		CallToolResult refused = call("create", Map.of("type", "Order", "fields", Map.of(
				"status", "OPEN", "number", "ES-1")));

		assertThat(refused.isError()).isTrue();
		Map<String, Object> error = structured(refused);
		assertThat(error).containsEntry("error", "constraint_violation");
		assertThat(list(error.get("violations"))).singleElement().satisfies(v -> {
			assertThat(v).containsEntry("constraint", "totalRequired").containsEntry("type", "Order");
			assertThat(String.valueOf(v.get("message"))).isNotBlank();
		});
		withRepository(repository -> assertThat(repository.getAllEObjects(ShopPackage.Literals.ORDER)
				.map(o -> ((Order) o).getNumber())).doesNotContain("ES-1"));

		CallToolResult saved = call("create", Map.of("type", "Order", "fields", Map.of(
				"status", "DRAFT", "number", "ES-1")));
		assertThat(saved.isError()).isFalse();
		// a draft may go without a total, an open order may not
		CallToolResult opened = call("update", Map.of("type", "Order", "id", structured(saved).get("id"),
				"fields", Map.of("status", "OPEN", "total", 0)));
		assertThat(opened.isError()).isTrue();
		assertThat(structured(opened)).containsEntry("error", "constraint_violation");
		CallToolResult withTotal = call("update", Map.of("type", "Order", "id", structured(saved).get("id"),
				"fields", Map.of("status", "OPEN", "total", "595.00")));
		assertThat(withTotal.isError()).isFalse();
		CallToolResult toZero = call("update", Map.of("type", "Order", "id", structured(saved).get("id"),
				"fields", Map.of("total", 0)));
		assertThat(toZero.isError()).isTrue();
		assertThat(structured(toZero)).containsEntry("error", "constraint_violation");
	}

	@Test
	void tellsWhatARequestGotWrong() {
		CallToolResult result = call("create", Map.of("type", "Order", "fields", Map.of(
				"status", "LOST", "total", "a lot", "colour", "red")));

		assertThat(result.isError()).isTrue();
		Map<String, Object> error = structured(result);
		assertThat(error).containsEntry("error", "invalid_request");
		assertThat(list(error.get("errors"))).extracting(e -> e.get("field"))
				.containsExactlyInAnyOrder("status", "total", "colour");
	}

	// ------------------------------------------------------------------ helpers

	@Test
	void takesTheStaticTokenFromThePreviewHeaderWithoutAuthorization() throws Exception {
		awaitTokenEndpoint();
		assertThat(postToTokenEndpoint(null, "Bearer " + STATIC_TOKEN).statusCode()).isEqualTo(200);
		assertThat(postToTokenEndpoint(null, null).statusCode()).isEqualTo(401);
		assertThat(postToTokenEndpoint(null, "Bearer wrong").statusCode()).isEqualTo(401);
		assertThat(postToTokenEndpoint(null, STATIC_TOKEN).statusCode()).as("without the Bearer scheme").isEqualTo(401);
	}

	@Test
	void aRealAuthorizationWinsOverThePreviewHeaderWithAStaticToken() throws Exception {
		awaitTokenEndpoint();
		assertThat(postToTokenEndpoint("Bearer " + STATIC_TOKEN, "Bearer wrong").statusCode()).isEqualTo(200);
		assertThat(postToTokenEndpoint("Bearer wrong", "Bearer " + STATIC_TOKEN).statusCode()).isEqualTo(401);
	}

	/** Until the servlet behind the static token is registered: it refuses a missing token then */
	private static void awaitTokenEndpoint() throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (postToTokenEndpoint(null, null).statusCode() != 401) {
			assertThat(System.currentTimeMillis()).as("/mcp-token not up").isLessThan(deadline);
			Thread.sleep(200);
		}
	}

	/** An initialize request to /mcp-token with the given Authorization and preview header, each when not null */
	private static HttpResponse<String> postToTokenEndpoint(String authorization, String preview) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CONTEXT_PATH + "/mcp-token"))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
						+ "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}"));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		if (preview != null) {
			request.header(PREVIEW, preview);
		}
		return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static CallToolResult call(String tool, Map<String, Object> arguments) {
		return client.callTool(new CallToolRequest(tool, arguments));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> structured(CallToolResult result) {
		assertThat(result.structuredContent()).as("structured content of %s", result.content()).isInstanceOf(Map.class);
		return (Map<String, Object>) result.structuredContent();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		assertThat(value).isInstanceOf(List.class);
		return (List<Map<String, Object>>) value;
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

	private static void withRepository(RepositoryWork work) throws Exception {
		ServiceObjects<Repository> objects = context.getServiceObjects(repositories.getServiceReference());
		Repository repository = objects.getService();
		try {
			work.run(repository);
		} finally {
			objects.ungetService(repository);
		}
	}

	@FunctionalInterface
	private interface RepositoryWork {
		void run(Repository repository) throws Exception;
	}
}
