/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.testing.filerepository;

import java.util.function.Function;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;

/**
 * Creates {@link FileRepositoryResource}s; the URI authority selects the store. Usable without
 * OSGi: {@code resourceSet.getResourceFactoryRegistry().getProtocolToFactoryMap()
 * .put(FileRepositoryConstants.URI_SCHEME, new FileRepositoryResourceFactory(stores))}.
 */
public class FileRepositoryResourceFactory implements Resource.Factory {

	private final Function<String, FileStore> stores;

	public FileRepositoryResourceFactory(Function<String, FileStore> stores) {
		this.stores = stores;
	}

	@Override
	public Resource createResource(URI uri) {
		return new FileRepositoryResource(uri, stores);
	}
}
