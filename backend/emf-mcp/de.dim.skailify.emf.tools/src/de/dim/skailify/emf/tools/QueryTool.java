/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.model.expression.Expression;
import org.eclipse.fennec.model.query.builder.QueryBuilder;
import org.eclipse.fennec.persistence.query.api.QueryResult;

import de.dim.skailify.emf.InvalidRequestException;
import de.dim.skailify.emf.ObjectFilter;
import de.dim.skailify.emf.ObjectJson;
import de.dim.skailify.mcp.AnnotatedMCPTool;

/** {@code query}: the stored objects of a type that match a filter, a page at a time. */
final class QueryTool extends ModelTool {

	QueryTool(ModelContext model) {
		super(model, "query", model.title("query objects"),
				"Finds stored objects of a type of the " + model.modelName() + " model (and its subtypes) and "
						+ "answers them with total count. Filter is a map from field to condition, all must hold: "
						+ "{\"status\": \"OPEN\"} equal, {\"status\": [\"OPEN\", \"PARTIALLY_PAID\"]} one of, "
						+ "{\"name\": {\"contains\": \"müller\"}} with an operator (eq, ne, in, contains, startsWith, "
						+ "gt, ge, lt, le, isNull; text matches ignore case), {\"supplier\": \"<id>\"} a reference, "
						+ "{\"supplier.name\": {\"contains\": \"x\"}} a field of a referenced object. Dates as "
						+ "YYYY-MM-DD, enums by literal. References in the answer are {type, id, label}.",
				schema(properties(model), "type"), AnnotatedMCPTool.query(null));
	}

	private static Map<String, Object> properties(ModelContext model) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("type", typeProperty(model.storedTypeNames(), "The type of the objects"));
		Map<String, Object> filter = property("object", "Conditions by field name, all must hold");
		properties.put("filter", filter);
		properties.put("orderBy", property("string", "An attribute to sort by; prefix - for descending, e.g. -invoiceDate"));
		properties.put("select", Map.of("type", "array", "items", Map.of("type", "string"), "description",
				"Only these fields of each object (type and id always)"));
		properties.put("skip", property("integer", "Objects to skip, for paging"));
		properties.put("top", property("integer", "Most objects to answer (default " + model.config().query_default_top()
				+ ", at most " + model.config().query_max_top() + ")"));
		return properties;
	}

	@Override
	Map<String, Object> call(Map<String, Object> arguments) throws ToolError, IOException {
		EClass eClass = model.storedType(string(arguments, "type", true));
		Expression filter;
		try {
			filter = ObjectFilter.expression(eClass, object(arguments, "filter", false));
		} catch (InvalidRequestException e) {
			throw ToolError.of(e);
		}
		int skip = Math.max(0, integer(arguments, "skip", 0));
		int top = Math.min(model.config().query_max_top(), Math.max(0, integer(arguments, "top", model.config().query_default_top())));
		String orderBy = string(arguments, "orderBy", false);
		EStructuralFeature order = null;
		boolean descending = false;
		if (orderBy != null) {
			descending = orderBy.startsWith("-");
			String name = descending ? orderBy.substring(1) : orderBy;
			order = eClass.getEStructuralFeature(name);
			if (!(order instanceof EAttribute) || order.isMany() || !ObjectJson.visible(order)) {
				throw invalid("orderBy", "no single-valued attribute " + name + " of " + eClass.getName());
			}
		}
		List<String> select = arguments.get("select") instanceof List<?> fields ? fields.stream().map(String::valueOf).toList() : null;
		EStructuralFeature sort = order;
		boolean desc = descending;
		return model.withRepository(repository -> {
			QueryBuilder counting = QueryBuilder.from(eClass);
			QueryBuilder paged = QueryBuilder.from(eClass);
			if (filter != null) {
				counting.where(filter);
				paged.where(EcoreUtil.copy(filter));
			}
			if (sort != null) {
				if (desc) {
					paged.orderByDesc(sort);
				} else {
					paged.orderByAsc(sort);
				}
			}
			paged.skip(skip).top(top);
			long total;
			try (QueryResult result = repository.find(counting.build(), null, null)) {
				total = result.objects().count();
			}
			List<Object> items = new ArrayList<>();
			try (QueryResult result = repository.find(paged.build(), null, null)) {
				for (EObject object : result.objects().toList()) {
					Map<String, Object> json = model.json.write(object);
					if (select != null) {
						json.keySet().removeIf(key -> !"type".equals(key) && !"id".equals(key) && !select.contains(key));
					}
					items.add(json);
				}
			}
			Map<String, Object> answer = new LinkedHashMap<>();
			answer.put("type", eClass.getName());
			answer.put("total", total);
			answer.put("skip", skip);
			answer.put("count", items.size());
			answer.put("items", items);
			return answer;
		});
	}
}
