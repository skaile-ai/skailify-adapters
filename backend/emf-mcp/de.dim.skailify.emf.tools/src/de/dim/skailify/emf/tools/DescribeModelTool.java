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

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;

import de.dim.skailify.mcp.AnnotatedMCPTool;

/** {@code describe_model}: the classes, fields, enums and constraints, or one class with its JSON schema. */
final class DescribeModelTool extends ModelTool {

	DescribeModelTool(ModelContext model) {
		super(model, "describe_model", model.title("describe the data model"),
				"Describes the " + model.modelName() + " data model: its classes with attributes, references, "
						+ "documentation and constraints (OCL expressions and their messages), and its enums. Classes "
						+ "marked stored can be queried, read and saved with the other tools; the others live inside "
						+ "a stored object. Give a type to get that class with the JSON schema of the fields that "
						+ "create and update take. Call this first.",
				schema(properties(model)), AnnotatedMCPTool.query(null));
	}

	private static Map<String, Object> properties(ModelContext model) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("type", typeProperty(model.types.eClasses().stream().map(EClass::getName).toList(),
				"A class to describe in detail, with the JSON schema of its fields"));
		return properties;
	}

	@Override
	Map<String, Object> call(Map<String, Object> arguments) throws ToolError {
		String type = string(arguments, "type", false);
		if (type == null) {
			return model.description.describe();
		}
		EClass eClass = model.types.eClass(type);
		if (eClass == null) {
			throw invalid("type", "unknown class " + type);
		}
		return model.description.describe(eClass);
	}
}
