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
package de.dim.skailify.emf.tools;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.dim.skailify.emf.FieldError;
import de.dim.skailify.emf.InvalidRequestException;
import de.dim.skailify.emf.ModelValidation.Violation;
import de.dim.skailify.mcp.AnnotatedMCPTool;
import de.dim.skailify.mcp.Caller;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import reactor.core.publisher.Mono;

/**
 * Base of the model tools: the definition, and the answer as JSON text plus the same map as
 * structured content. What the agent got wrong comes back as an {@code isError} result with an
 * {@code error} code and the details, so it can correct the call:
 * {@code invalid_request} (with {@code errors}), {@code constraint_violation} (with
 * {@code violations}), {@code not_found}, {@code conflict}, {@code referenced},
 * {@code insufficient_scope} (with the {@code scope} needed).
 */
abstract class ModelTool implements AnnotatedMCPTool {

	private static final Logger LOG = Logger.getLogger(ModelTool.class.getName());

	/** A call that cannot be answered, with the code and details for the agent */
	static final class ToolError extends Exception {

		private static final long serialVersionUID = 1L;

		final transient Map<String, Object> body;

		ToolError(String code, String message) {
			super(message);
			body = new LinkedHashMap<>();
			body.put("error", code);
			body.put("message", message);
		}

		ToolError with(String key, Object value) {
			body.put(key, value);
			return this;
		}

		static ToolError of(InvalidRequestException e) {
			return new ToolError("invalid_request", "The request does not fit the model; correct the fields named in errors.")
					.with("errors", e.getErrors().stream().map(FieldError::toMap).toList());
		}

		static ToolError of(List<Violation> violations) {
			return new ToolError("constraint_violation", "Not saved: the object violates constraints of the model.")
					.with("violations", violations.stream().map(Violation::toMap).toList());
		}
	}

	protected final ModelContext model;
	private final String name;
	private final String title;
	private final String description;
	private final String inputSchema;
	private final ToolAnnotations annotations;

	ModelTool(ModelContext model, String name, String title, String description, Map<String, Object> inputSchema,
			ToolAnnotations annotations) {
		this.model = model;
		this.name = model.prefix() + name;
		this.title = title;
		this.description = description;
		this.inputSchema = model.json(inputSchema);
		this.annotations = annotations;
	}

	/** The answer to the call, a JSON-like map */
	abstract Map<String, Object> call(Map<String, Object> arguments) throws ToolError, IOException;

	/**
	 * Runs the call as the caller. When the server verifies callers (upstream gap: the
	 * {@link Caller} comes from the context extractor of {@code SkailifyMCPServer}), the caller
	 * needs the read or write scope of the configuration, and every change is logged with who
	 * made it: the model has no attribute to record a modifier in. Without a verified caller (a
	 * static token, loopback) the app is single-user, as before.
	 */
	@Override
	public Mono<CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		Optional<Caller> caller = Caller.of(exchange);
		return Mono.fromCallable(() -> {
			Map<String, Object> args = arguments == null ? Map.of() : arguments;
			String who = caller.map(Caller::display).orElse("the local user");
			try {
				String scope = requiredScope();
				if (caller.isPresent() && !caller.get().hasScope(scope)) {
					LOG.info(() -> name + " refused to " + who + ": scope " + scope + " missing");
					throw new ToolError("insufficient_scope", "The caller's token lacks the scope " + scope + " this tool needs.")
							.with("scope", scope);
				}
				Map<String, Object> answer = call(args);
				if (!readOnly()) {
					LOG.info(() -> name + " " + target(args, answer) + " by " + who);
				} else {
					LOG.fine(() -> name + " by " + who);
				}
				return result(answer, false);
			} catch (ToolError e) {
				return result(e.body, true);
			} catch (IOException e) {
				LOG.log(Level.FINE, e, () -> name + " failed");
				return result(new ToolError("repository", e.getMessage() == null ? e.toString() : e.getMessage()).body, true);
			}
		});
	}

	boolean readOnly() {
		return AnnotatedMCPTool.readOnly(annotations);
	}

	/** The scope the configuration asks for this tool, or blank */
	String requiredScope() {
		return readOnly() ? model.config().scope_read() : model.config().scope_write();
	}

	/** What a change was about, for the log: type and id */
	private static String target(Map<String, Object> arguments, Map<String, Object> answer) {
		Object type = answer.getOrDefault("type", arguments.get("type"));
		Object id = answer.getOrDefault("id", arguments.get("id"));
		return type + (id == null ? "" : " " + id);
	}

	private CallToolResult result(Map<String, Object> body, boolean error) {
		McpJsonMapper json = model.jsonMapper();
		String text;
		try {
			text = json.writeValueAsString(body);
		} catch (IOException e) {
			text = String.valueOf(body);
		}
		return CallToolResult.builder().addTextContent(text).structuredContent(body).isError(error).build();
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public String getInputSchema() {
		return inputSchema;
	}

	@Override
	public String getOutputSchema() {
		return null;
	}

	@Override
	public ToolAnnotations getAnnotations() {
		return annotations;
	}

	// ------------------------------------------------------------ argument helpers

	static String string(Map<String, Object> arguments, String key, boolean required) throws ToolError {
		Object value = arguments.get(key);
		if (value == null || String.valueOf(value).isBlank()) {
			if (required) {
				throw invalid(key, "is required");
			}
			return null;
		}
		return String.valueOf(value).strip();
	}

	static int integer(Map<String, Object> arguments, String key, int defaultValue) throws ToolError {
		Object value = arguments.get(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).strip());
		} catch (NumberFormatException e) {
			throw invalid(key, "expects an integer");
		}
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> object(Map<String, Object> arguments, String key, boolean required) throws ToolError {
		Object value = arguments.get(key);
		if (value == null) {
			if (required) {
				throw invalid(key, "is required");
			}
			return Map.of();
		}
		if (!(value instanceof Map)) {
			throw invalid(key, "expects an object");
		}
		return (Map<String, Object>) value;
	}

	static ToolError invalid(String field, String message) {
		return ToolError.of(new InvalidRequestException(field, message));
	}

	// ------------------------------------------------------------ schema helpers

	static Map<String, Object> schema(Map<String, Object> properties, String... required) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		if (required.length > 0) {
			schema.put("required", List.of(required));
		}
		return schema;
	}

	static Map<String, Object> property(String type, String description) {
		Map<String, Object> property = new LinkedHashMap<>();
		property.put("type", type);
		property.put("description", description);
		return property;
	}

	static Map<String, Object> typeProperty(List<String> names, String description) {
		Map<String, Object> property = property("string", description);
		property.put("enum", names);
		return property;
	}
}
