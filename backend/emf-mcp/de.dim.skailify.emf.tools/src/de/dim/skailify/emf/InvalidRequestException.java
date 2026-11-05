/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf;

import java.util.List;
import java.util.stream.Collectors;

/** A request that names unknown types or fields, or values that do not fit their type. */
public class InvalidRequestException extends Exception {

	private static final long serialVersionUID = 1L;

	private final transient List<FieldError> errors;

	public InvalidRequestException(List<FieldError> errors) {
		super(errors.stream().map(e -> e.field() + ": " + e.message()).collect(Collectors.joining("; ")));
		this.errors = List.copyOf(errors);
	}

	public InvalidRequestException(String field, String message) {
		this(List.of(new FieldError(field, message)));
	}

	public List<FieldError> getErrors() {
		return errors;
	}
}
