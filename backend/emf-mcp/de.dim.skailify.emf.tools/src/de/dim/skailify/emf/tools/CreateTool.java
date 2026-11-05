/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf.tools;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

import de.dim.skailify.emf.InvalidRequestException;
import de.dim.skailify.mcp.AnnotatedMCPTool;

/** {@code create}: a new stored object, checked against the model before it is saved. */
final class CreateTool extends ModelTool {

	CreateTool(ModelContext model) {
		super(model, "create", model.title("create an object"),
				"Creates and saves a new object of the " + model.modelName() + " model and answers it with its "
						+ "new id. Fields by name as describe_model(type) lists them: contained objects nested, "
						+ "references to stored objects as {\"type\": \"Customer\", \"id\": \"...\"}, to an object "
						+ "contained in this one as {\"path\": \"lineItems/0\"}. Decimals preferably as strings. "
						+ "Nothing is saved when a value does not fit, a unique value is taken or a constraint of "
						+ "the model is violated; the error says what to correct.",
				schema(properties(model), "type", "fields"), AnnotatedMCPTool.effect(null, false, false));
	}

	private static Map<String, Object> properties(ModelContext model) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("type", typeProperty(model.creatableTypeNames(), "The type of the new object"));
		properties.put("fields", property("object", "The fields of the new object, without id"));
		return properties;
	}

	@Override
	Map<String, Object> call(Map<String, Object> arguments) throws ToolError, IOException {
		EClass eClass = model.creatableType(string(arguments, "type", true));
		Map<String, Object> fields = object(arguments, "fields", true);
		return model.withRepository(repository -> {
			EObject object;
			try {
				object = model.json.create(eClass, fields, (type, id) -> model.find(repository, type, id));
			} catch (InvalidRequestException e) {
				throw ToolError.of(e);
			}
			model.check(repository, object);
			repository.saveAll(List.of(object));
			return model.json.write(object);
		});
	}
}
