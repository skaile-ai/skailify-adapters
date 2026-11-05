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
package de.dim.skailify.emf;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.fennec.model.expression.Expression;
import org.eclipse.fennec.model.query.builder.Expressions;
import org.eclipse.fennec.model.query.builder.Expressions.PathStep;

/**
 * A simple filter as a Fennec query expression. The filter is a map from field name to
 * condition; all conditions must hold:
 * <ul>
 * <li>{@code "status": "OPEN"} – equal</li>
 * <li>{@code "status": ["OPEN", "PARTIALLY_PAID"]} – one of</li>
 * <li>{@code "name": {"contains": "müller"}} – an operator: {@code eq, ne, in, contains,
 * startsWith, gt, ge, lt, le, isNull} (string matches ignore case)</li>
 * <li>{@code "customer": "<id>"} or {@code {"type": "Customer", "id": "<id>"}} – a single
 * reference to the object with that id</li>
 * <li>{@code "supplier.name": {"contains": "müller"}} – an attribute of a single reference</li>
 * </ul>
 */
public final class ObjectFilter {

	private ObjectFilter() {
	}

	/** The expression for the filter on the class, or null when the filter is empty */
	public static Expression expression(EClass eClass, Map<String, ?> filter) throws InvalidRequestException {
		if (filter == null || filter.isEmpty()) {
			return null;
		}
		List<FieldError> errors = new ArrayList<>();
		List<Expression> conditions = new ArrayList<>();
		for (Map.Entry<String, ?> entry : filter.entrySet()) {
			try {
				conditions.add(condition(eClass, entry.getKey(), entry.getValue()));
			} catch (IllegalArgumentException e) {
				errors.add(new FieldError("filter." + entry.getKey(), e.getMessage()));
			}
		}
		if (!errors.isEmpty()) {
			throw new InvalidRequestException(errors);
		}
		return conditions.size() == 1 ? conditions.get(0) : Expressions.and(conditions.toArray(Expression[]::new));
	}

	private static Expression condition(EClass eClass, String field, Object condition) {
		List<EStructuralFeature> path = new ArrayList<>();
		EClass current = eClass;
		String[] segments = field.split("\\.");
		for (int i = 0; i < segments.length; i++) {
			EStructuralFeature feature = current == null ? null : current.getEStructuralFeature(segments[i]);
			if (feature == null || !ObjectJson.visible(feature)) {
				throw new IllegalArgumentException("unknown field" + (current == null ? "" : " of " + current.getName()));
			}
			if (feature.isMany()) {
				throw new IllegalArgumentException(feature.getName() + " has many values; filters take single-valued fields");
			}
			path.add(feature);
			current = feature instanceof EReference reference ? reference.getEReferenceType() : null;
			if (i < segments.length - 1 && current == null) {
				throw new IllegalArgumentException(feature.getName() + " is no reference");
			}
		}
		EStructuralFeature last = path.get(path.size() - 1);
		if (last instanceof EReference reference) {
			EAttribute id = reference.getEReferenceType().getEIDAttribute();
			if (id == null) {
				throw new IllegalArgumentException(reference.getEReferenceType().getName() + " has no id to compare");
			}
			path.add(id);
			condition = referenceIds(condition);
		}
		EAttribute attribute = (EAttribute) path.get(path.size() - 1);
		PathStep step = Expressions.path(path.toArray(EStructuralFeature[]::new));
		if (condition instanceof Map<?, ?> operators) {
			List<Expression> parts = new ArrayList<>();
			for (Map.Entry<?, ?> operator : operators.entrySet()) {
				parts.add(operator(step, attribute, String.valueOf(operator.getKey()), operator.getValue()));
			}
			if (parts.isEmpty()) {
				throw new IllegalArgumentException("no operator given");
			}
			return parts.size() == 1 ? parts.get(0) : Expressions.and(parts.toArray(Expression[]::new));
		}
		if (condition instanceof Collection<?> values) {
			return step.in(values.stream().map(v -> literal(attribute, v)).toArray());
		}
		return condition == null ? step.isNull() : step.eq(literal(attribute, condition));
	}

	/** A reference condition compares ids: {type, id} maps become their id */
	private static Object referenceIds(Object condition) {
		if (condition instanceof Map<?, ?> map && map.containsKey(ObjectJson.ID)) {
			return map.get(ObjectJson.ID);
		}
		if (condition instanceof Collection<?> values) {
			return values.stream().map(ObjectFilter::referenceIds).toList();
		}
		if (condition instanceof Map<?, ?> map) {
			Map<Object, Object> operators = new LinkedHashMap<>();
			map.forEach((k, v) -> operators.put(k, referenceIds(v)));
			return operators;
		}
		return condition;
	}

	private static Expression operator(PathStep step, EAttribute attribute, String operator, Object value) {
		return switch (operator) {
		case "eq" -> value == null ? step.isNull() : step.eq(literal(attribute, value));
		case "ne" -> value == null ? step.isNotNull() : step.ne(literal(attribute, value));
		case "in" -> step.in((value instanceof Collection<?> values ? values.stream() : Stream.of(value))
				.map(v -> literal(attribute, v)).toArray());
		case "contains" -> step.containsIgnoreCase(String.valueOf(value));
		case "startsWith" -> step.startsWithIgnoreCase(String.valueOf(value));
		case "gt" -> step.gt(literal(attribute, value));
		case "ge" -> step.ge(literal(attribute, value));
		case "lt" -> step.lt(literal(attribute, value));
		case "le" -> step.le(literal(attribute, value));
		case "isNull" -> Boolean.FALSE.equals(value) || "false".equals(value) ? step.isNotNull() : step.isNull();
		default -> throw new IllegalArgumentException("unknown operator " + operator
				+ "; one of eq, ne, in, contains, startsWith, gt, ge, lt, le, isNull");
		};
	}

	/** The value as the attribute's type, in a form the query literals take */
	private static Object literal(EAttribute attribute, Object value) {
		Object converted = Values.convert(attribute.getEAttributeType(), value);
		if (converted instanceof BigDecimal decimal) {
			return decimal.doubleValue();
		}
		if (converted instanceof BigInteger integer) {
			return integer.longValue();
		}
		return converted;
	}
}
