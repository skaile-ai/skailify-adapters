/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.testing.filerepository;

/**
 * Constants of the file repository flavour.
 */
public final class FileRepositoryConstants {

	/** URI scheme of file repository collections: {@code filerepo://<repositoryId>/<EClassName>}. */
	public static final String URI_SCHEME = "filerepo";

	/** Factory PID of a file repository configuration. */
	public static final String FACTORY_PID = "fennec.repository.file";

	private FileRepositoryConstants() {
	}
}
