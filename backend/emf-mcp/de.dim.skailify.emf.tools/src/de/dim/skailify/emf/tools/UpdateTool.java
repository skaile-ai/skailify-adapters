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
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

import de.dim.skailify.emf.InvalidRequestException;
import de.dim.skailify.mcp.AnnotatedMCPTool;

/** {@code update}: changes fields of a stored object, checked against the model before it is saved. */
final class UpdateTool extends ModelTool {

	UpdateTool(ModelContext model) {
		super(model, "update", model.title("update an object"),
				"Changes the given fields of a stored object of the " + model.modelName() + " model and answers "
						+ "the saved object; fields left out stay as they are, a field set to null is cleared. A list "
						+ "of contained objects is replaced as a whole: give the id of a contained object to change "
						+ "it, leave one out to remove it. References and values as for create. Nothing is saved when "
						+ "a value does not fit, a unique value is taken or a constraint is violated.",
				schema(properties(model), "type", "id", "fields"), AnnotatedMCPTool.effect(null, true, true));
	}

	private static Map<String, Object> properties(ModelContext model) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("type", typeProperty(model.storedTypeNames(), "The type of the object"));
		properties.put("id", property("string", "The id of the object"));
		properties.put("fields", property("object", "The fields to change"));
		return properties;
	}

	@Override
	Map<String, Object> call(Map<String, Object> arguments) throws ToolError, IOException {
		EClass eClass = model.storedType(string(arguments, "type", true));
		String id = string(arguments, "id", true);
		Map<String, Object> fields = object(arguments, "fields", true);
		return model.withRepository(repository -> {
			EObject object = model.require(repository, eClass, id);
			try {
				model.json.update(object, fields, (type, refId) -> model.find(repository, type, refId));
			} catch (InvalidRequestException e) {
				throw ToolError.of(e);
			}
			model.check(repository, object);
			repository.save(object);
			return model.json.write(object);
		});
	}
}
