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
import org.eclipse.emf.ecore.EObject;

import de.dim.skailify.mcp.AnnotatedMCPTool;

/** {@code delete}: removes a stored object that nothing references any more. */
final class DeleteTool extends ModelTool {

	DeleteTool(ModelContext model) {
		super(model, "delete", model.title("delete an object"),
				"Deletes one stored object of the " + model.modelName() + " model, with the objects it contains. "
						+ "Refused (error referenced) while other objects still reference it.",
				schema(properties(model), "type", "id"), AnnotatedMCPTool.effect(null, true, true));
	}

	private static Map<String, Object> properties(ModelContext model) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("type", typeProperty(model.storedTypeNames(), "The type of the object"));
		properties.put("id", property("string", "The id of the object"));
		return properties;
	}

	@Override
	Map<String, Object> call(Map<String, Object> arguments) throws ToolError, IOException {
		EClass eClass = model.storedType(string(arguments, "type", true));
		String id = string(arguments, "id", true);
		return model.withRepository(repository -> {
			EObject object = model.require(repository, eClass, id);
			Map<String, Object> deleted = model.json.reference(object, null);
			try {
				repository.delete(object);
			} catch (IOException e) {
				// the repositories refuse to delete what is still referenced
				throw new ToolError("referenced", "Not deleted: " + e.getMessage());
			}
			Map<String, Object> answer = new LinkedHashMap<>();
			answer.put("deleted", deleted);
			return answer;
		});
	}
}
