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
