/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What is wrong with one field of a request.
 *
 * @param field the path of the field in the request, e.g. {@code lineItems[0].net}
 * @param message what is wrong, for the agent to correct it
 */
public record FieldError(String field, String message) {

	/** As a JSON-like map */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("field", field);
		map.put("message", message);
		return map;
	}
}
