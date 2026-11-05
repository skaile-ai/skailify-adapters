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
package de.dim.skailify.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.dim.skailify.emf.SkailifyAnnotations;
import de.dim.skailify.manifest.SkailifyManifest.Tool;
import de.dim.skailify.manifest.ToolGrant.TypeGrant;
import de.dim.skailify.mcp.AnnotatedMCPTool;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import reactor.core.publisher.Mono;

/**
 * The manifest document and its grant catalogue, over a small dynamic model whose {@code skailify}
 * annotation gives grants, effect classes and approvals: inherited from a supertype and the
 * package, values outside the vocabulary ignored, hidden classes left out.
 */
class SkailifyManifestTest {

	static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();
	static final String NS = "urn:test:bank";
	static EPackage pkg;
	static EClass base, account, profile, payment, note;

	@BeforeAll
	static void model() {
		EcoreFactory f = EcoreFactory.eINSTANCE;
		pkg = f.createEPackage();
		pkg.setName("bank");
		pkg.setNsURI(NS);
		annotate(pkg, "effectClass", "routine", "approval", "required");
		base = eClass("Base", true);
		annotate(base, "grant", "bank.base", "effectClass", "privileged");
		account = eClass("Account", false);
		account.getESuperTypes().add(base);
		profile = eClass("Profile", false);
		annotate(profile, "expose", "false", "grant", "bank.hidden");
		payment = eClass("Payment", false);
		annotate(payment, "approval", "optional", "effectClass", "dangerous");
		note = eClass("Note", false);
	}

	@Test
	void readsGrantsInheritedFromSupertypesAndPackage() {
		assertThat(GrantAnnotations.grant(account)).isEqualTo("bank.base");
		assertThat(GrantAnnotations.effectClass(account)).isEqualTo("privileged");
		assertThat(GrantAnnotations.approval(account)).isEqualTo("required");
		assertThat(GrantAnnotations.grant(payment)).isNull();
		// "dangerous" is no effect class: the package's routine applies
		assertThat(GrantAnnotations.effectClass(payment)).isEqualTo("routine");
		assertThat(GrantAnnotations.approval(payment)).isEqualTo("optional");
	}

	@Test
	void refinesAnEffectPerTypeAndLeavesHiddenTypesOut() {
		ToolGrant grant = GrantAnnotations.effect("update", "bank:write", "Update", pkg);

		assertThat(grant.effectClass()).isEqualTo("routine");
		assertThat(grant.approval()).isEqualTo("required");
		assertThat(grant.types()).containsExactly(
				new TypeGrant("Account", "bank.base", "privileged", "required", null),
				new TypeGrant("Payment", null, "routine", "optional", null));
	}

	@Test
	@SuppressWarnings("unchecked")
	void listsTheAppEndpointAuthAndGrants() {
		List<ToolGrant> actions = SkailifyManifest.actions(new String[] {
				"{\"name\": \"app.bank.confirm\", \"effectClass\": \"routine\", \"approval\": \"optional\", "
						+ "\"description\": \"Confirms\"}",
				"{\"name\": \"app.bank.get_view_state\", \"kind\": \"query\"}",
				"{\"effectClass\": \"routine\"}", "not json", "" }, JSON);
		assertThat(actions).extracting(ToolGrant::name).containsExactly("app.bank.confirm", "app.bank.get_view_state");
		SkailifyManifest.Settings settings = new SkailifyManifest.Settings("bank", "Bank", "Accounts", "", "/", "/mcp",
				true, List.of("https://id.example.org/realms/example"), List.of("bank:read"), actions);

		Map<String, Object> document = SkailifyManifest.document(settings, "https://bank.example.org/preview/x",
				List.of(new Tool(tool("update", AnnotatedMCPTool.effect("Bank: update", true, true)), "bank:write", NS),
						new Tool(tool("query", AnnotatedMCPTool.query("Bank: query")), "bank:read", NS),
						new Tool(tool("plain", null), null, null)),
				Map.of(NS, pkg)::get);

		assertThat(document).containsEntry("skailify", "0.1-draft");
		assertThat((Map<String, Object>) document.get("app")).containsEntry("id", "bank")
				.containsEntry("embedUrl", "https://bank.example.org/preview/x/");
		assertThat((Map<String, Object>) document.get("mcp")).containsEntry("url", "https://bank.example.org/preview/x/mcp");
		assertThat((Map<String, Object>) document.get("auth"))
				.containsEntry("type", "oauth2")
				.containsEntry("issuers", List.of("https://id.example.org/realms/example"))
				.containsEntry("protectedResourceMetadata",
						"https://bank.example.org/preview/x/.well-known/oauth-protected-resource/mcp");

		List<Map<String, Object>> grants = (List<Map<String, Object>>) document.get("grants");
		assertThat(grants).extracting(g -> g.get("name")).containsExactly("plain", "query", "update",
				"app.bank.confirm", "app.bank.get_view_state");
		// a tool that says nothing counts as an effect that needs approval
		assertThat(grants.get(0)).containsEntry("channel", "mcp").containsEntry("kind", "effect")
				.containsEntry("approval", "required").doesNotContainKey("types");
		assertThat(grants.get(1)).containsEntry("kind", "query").containsEntry("effectClass", "routine")
				.containsEntry("approval", "none").containsEntry("scope", "bank:read")
				.containsEntry("description", "Bank: query");
		assertThat(grants.get(2)).containsEntry("kind", "effect").containsEntry("scope", "bank:write")
				.containsEntry("types", List.of(
						Map.of("type", "Account", "grant", "bank.base", "effectClass", "privileged", "approval", "required"),
						Map.of("type", "Payment", "effectClass", "routine", "approval", "optional")));
		assertThat(grants.get(3)).containsEntry("channel", "app").containsEntry("approval", "optional");
		assertThat(grants.get(4)).containsEntry("kind", "query").containsEntry("approval", "none");
	}

	@Test
	@SuppressWarnings("unchecked")
	void saysWhenThereIsNoAuth() {
		SkailifyManifest.Settings settings = new SkailifyManifest.Settings("a", "A", "", "", "https://ui.example.org/a",
				"mcp", false, List.of(), List.of(), List.of());
		Map<String, Object> document = SkailifyManifest.document(settings, "http://localhost:8090", List.of(), ns -> null);
		assertThat((Map<String, Object>) document.get("auth")).containsExactly(Map.entry("type", "none"));
		assertThat((Map<String, Object>) document.get("app")).containsEntry("embedUrl", "https://ui.example.org/a");
		assertThat((Map<String, Object>) document.get("mcp")).containsEntry("url", "http://localhost:8090/mcp");
	}

	@Test
	void refusesEffectClassesOutsideTheVocabulary() {
		assertThatThrownBy(() -> new ToolGrant("x", ToolGrant.EFFECT, "dangerous", ToolGrant.NONE, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TypeGrant("T", null, ToolGrant.ROUTINE, "maybe", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static void annotate(EModelElement element, String... keysAndValues) {
		for (int i = 0; i < keysAndValues.length; i += 2) {
			EcoreUtil.setAnnotation(element, SkailifyAnnotations.SOURCE, keysAndValues[i], keysAndValues[i + 1]);
		}
	}

	private static EClass eClass(String name, boolean isAbstract) {
		EClass eClass = EcoreFactory.eINSTANCE.createEClass();
		eClass.setName(name);
		eClass.setAbstract(isAbstract);
		pkg.getEClassifiers().add(eClass);
		return eClass;
	}

	private static MCPTool tool(String name, ToolAnnotations annotations) {
		return new AnnotatedMCPTool() {

			@Override
			public String getName() {
				return name;
			}

			@Override
			public String getDescription() {
				return null;
			}

			@Override
			public String getInputSchema() {
				return "{\"type\":\"object\"}";
			}

			@Override
			public String getOutputSchema() {
				return null;
			}

			@Override
			public ToolAnnotations getAnnotations() {
				return annotations;
			}

			@Override
			public Mono<CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
				return Mono.empty();
			}
		};
	}
}
