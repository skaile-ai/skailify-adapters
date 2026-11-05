/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Generic EMF support for agent-facing data access, derived from an EPackage only: objects as
 * JSON-like maps (references as {@code {type, id}}), a description of the model with JSON
 * schemas, simple filters turned into Fennec queries, and validation results an agent can act on.
 * Nothing here knows a particular model; plain Java over EMF, no OSGi services.
 * <p>
 */
@org.osgi.annotation.bundle.Export
@org.osgi.annotation.versioning.Version("0.2.0")
package de.dim.skailify.emf;
