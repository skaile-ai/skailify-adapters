/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf.tools;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/** One set of model tools: one EPackage over one repository. */
@ObjectClassDefinition(name = "Skailify EMF model tools",
		description = "MCP tools to describe, query and change the objects of one EPackage in one Fennec repository")
public @interface ModelToolsConfig {

	@AttributeDefinition(description = "Name of this tool set; the tools carry it as service property skailify.model, "
			+ "for the tool provider's tools.target")
	String skailify_model();

	@AttributeDefinition(description = "Filter selecting the EPackage service, e.g. (emf.nsURI=https://example.org/model/1.0)")
	String epackage_target();

	@AttributeDefinition(description = "Filter selecting the Repository service, e.g. (persistence.repository.id=example)")
	String repository_target();

	@AttributeDefinition(description = "Prefix of the tool names, e.g. shop_ when one server hosts several models")
	String tool_prefix() default "";

	@AttributeDefinition(description = "Classes the tools do not show")
	String[] types_excluded() default {};

	@AttributeDefinition(description = "Attributes unique per class (ignoring case), as DeclaringClass.attribute")
	String[] unique_attributes() default {};

	@AttributeDefinition(description = "Annotation source under which a class keeps a message per constraint name")
	String messages_annotation_source() default "";

	@AttributeDefinition(description = "Whether EMF's structural checks (multiplicities, ...) also refuse a save, "
			+ "not only the model's constraints")
	boolean structural_validation() default false;

	@AttributeDefinition(description = "Attributes that label a referenced object, first set one wins")
	String[] label_attributes() default { "name" };

	@AttributeDefinition(description = "Only describe, query and get: no create, update or delete tools")
	boolean read_only() default false;

	@AttributeDefinition(description = "OAuth scope a caller needs for describe_model, query and get; empty = none. "
			+ "Only checked when the server verifies callers (SkailifyMCPServer with oauth.enabled)")
	String scope_read() default "";

	@AttributeDefinition(description = "OAuth scope a caller needs for create, update and delete; empty = none. "
			+ "Only checked when the server verifies callers (SkailifyMCPServer with oauth.enabled)")
	String scope_write() default "";

	@AttributeDefinition(description = "Objects a query answers when it gives no top")
	int query_default_top() default 50;

	@AttributeDefinition(description = "Most objects one query answers")
	int query_max_top() default 500;
}
