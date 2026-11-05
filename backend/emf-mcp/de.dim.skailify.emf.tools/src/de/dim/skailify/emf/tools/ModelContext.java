/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf.tools;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.fennec.persistence.repository.api.Repository;
import org.osgi.service.component.ComponentServiceObjects;

import de.dim.skailify.emf.ModelDescription;
import de.dim.skailify.emf.ModelTypes;
import de.dim.skailify.emf.ModelValidation;
import de.dim.skailify.emf.ModelValidation.Violation;
import de.dim.skailify.emf.ObjectJson;
import de.dim.skailify.emf.UniqueValues;
import de.dim.skailify.emf.tools.ModelTool.ToolError;
import io.modelcontextprotocol.json.McpJsonMapper;

/** What the tools of one configuration share: the model, its helpers and the repository. */
final class ModelContext {

	@FunctionalInterface
	interface RepositoryWork<T> {
		T run(Repository repository) throws ToolError, IOException;
	}

	private final ModelToolsConfig config;
	private final ComponentServiceObjects<Repository> repositories;
	private final McpJsonMapper jsonMapper;
	final ModelTypes types;
	final ObjectJson json;
	final ModelDescription description;
	final ModelValidation validation;
	final UniqueValues unique;

	ModelContext(ModelToolsConfig config, EPackage ePackage,
			ComponentServiceObjects<Repository> repositories, McpJsonMapper jsonMapper) {
		this.config = config;
		this.repositories = repositories;
		this.jsonMapper = jsonMapper;
		String messages = config.messages_annotation_source().isBlank() ? null : config.messages_annotation_source();
		types = new ModelTypes(ePackage, Arrays.asList(config.types_excluded()));
		json = new ObjectJson(types, Arrays.asList(config.label_attributes()));
		description = new ModelDescription(types, messages);
		validation = new ModelValidation(messages, config.structural_validation());
		unique = new UniqueValues(Arrays.asList(config.unique_attributes()));
	}

	String prefix() {
		return config.tool_prefix();
	}

	ModelToolsConfig config() {
		return config;
	}

	McpJsonMapper jsonMapper() {
		return jsonMapper;
	}

	String modelName() {
		return types.ePackage().getName();
	}

	/** A tool title for people: the model's name and what the tool does */
	String title(String action) {
		String name = modelName();
		return (name.isEmpty() ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1) + ": ") + action;
	}

	String json(Map<String, Object> value) {
		try {
			return jsonMapper.writeValueAsString(value);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Names of the types a query or get accepts: stored classes and their abstract supertypes */
	List<String> storedTypeNames() {
		List<EClass> stored = types.rootClasses();
		return types.eClasses().stream()
				.filter(c -> stored.contains(c) || types.concreteSubtypes(c).stream().anyMatch(stored::contains))
				.map(EClass::getName).toList();
	}

	/** Names of the types create accepts: concrete stored classes */
	List<String> creatableTypeNames() {
		return types.rootClasses().stream().map(EClass::getName).toList();
	}

	EClass storedType(String name) throws ToolError {
		EClass eClass = types.eClass(name);
		if (eClass == null || !storedTypeNames().contains(name)) {
			throw ModelTool.invalid("type", "unknown type " + name + "; stored types: " + storedTypeNames());
		}
		return eClass;
	}

	EClass creatableType(String name) throws ToolError {
		EClass eClass = types.eClass(name);
		if (eClass == null || !creatableTypeNames().contains(name)) {
			throw ModelTool.invalid("type", name + " cannot be created on its own; types: " + creatableTypeNames());
		}
		return eClass;
	}

	/** The stored object, looked up among the concrete stored subtypes of an abstract type */
	EObject find(Repository repository, EClass type, String id) throws IOException {
		if (!type.isAbstract() && !type.isInterface()) {
			EObject object = repository.getEObject(type, id);
			return object != null && type.isInstance(object) ? object : null;
		}
		List<EClass> stored = types.rootClasses();
		for (EClass candidate : types.concreteSubtypes(type)) {
			if (stored.contains(candidate)) {
				EObject object = repository.getEObject(candidate, id);
				if (object != null && candidate.isInstance(object)) {
					return object;
				}
			}
		}
		return null;
	}

	EObject require(Repository repository, EClass type, String id) throws ToolError, IOException {
		EObject object = find(repository, type, id);
		if (object == null) {
			throw new ToolError("not_found", "No " + type.getName() + " with id " + id);
		}
		return object;
	}

	/**
	 * Refuses a save the model does not allow: values that must be unique per class and the
	 * model's constraints. Nothing is saved when this throws.
	 */
	void check(Repository repository, EObject object) throws ToolError, IOException {
		if (!unique.attributes(object.eClass()).isEmpty()) {
			List<EObject> existing = repository.getAllEObjects(object.eClass()).toList();
			Optional<UniqueValues.Conflict> conflict = unique.taken(List.of(object), existing);
			if (conflict.isPresent()) {
				UniqueValues.Conflict c = conflict.get();
				ToolError error = new ToolError("conflict", "Another " + c.type() + " already has " + c.feature().getName()
						+ " \"" + c.value() + "\"");
				error.with("field", c.feature().getName());
				if (c.ownerId() != null) {
					error.with("existing", Map.of("type", c.type(), "id", c.ownerId()));
				}
				throw error;
			}
		}
		List<Violation> violations = validation.validate(List.of(object));
		if (!violations.isEmpty()) {
			throw ToolError.of(violations);
		}
	}

	/** Runs the work on a repository instance of its own, released afterwards */
	<T> T withRepository(RepositoryWork<T> work) throws ToolError, IOException {
		Repository repository = repositories.getService();
		try {
			return work.run(repository);
		} finally {
			repositories.ungetService(repository);
		}
	}
}
