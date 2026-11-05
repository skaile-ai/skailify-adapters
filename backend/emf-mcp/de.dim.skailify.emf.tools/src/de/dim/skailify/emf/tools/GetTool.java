/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf.tools;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;

import de.dim.skailify.mcp.AnnotatedMCPTool;

/** {@code get}: one stored object with everything it contains. */
final class GetTool extends ModelTool {

	GetTool(ModelContext model) {
		super(model, "get", model.title("get an object"),
				"Reads one stored object of the " + model.modelName() + " model by type and id, with the objects "
						+ "it contains. References to other objects are {type, id, label}.",
				schema(properties(model), "type", "id"), AnnotatedMCPTool.query(null));
	}

	private static Map<String, Object> properties(ModelContext model) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("type", typeProperty(model.storedTypeNames(), "The type of the object (an abstract type finds any subtype)"));
		properties.put("id", property("string", "The id of the object"));
		return properties;
	}

	@Override
	Map<String, Object> call(Map<String, Object> arguments) throws ToolError, IOException {
		EClass eClass = model.storedType(string(arguments, "type", true));
		String id = string(arguments, "id", true);
		return model.withRepository(repository -> model.json.write(model.require(repository, eClass, id)));
	}
}
