/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.testing.filerepository;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.service.component.annotations.Component;

/**
 * The stores of all configured file repositories, by repository id (= URI authority).
 * Shared by the repository components (which register their store) and the resource factory
 * (which resolves a URI authority to its store).
 */
@Component(service = FileStoreRegistry.class)
public class FileStoreRegistry {

	private final Map<String, FileStore> stores = new ConcurrentHashMap<>();

	public FileStore register(String repositoryId, Path directory) {
		FileStore store = new FileStore(repositoryId, directory);
		FileStore existing = stores.putIfAbsent(repositoryId, store);
		if (existing != null) {
			throw new IllegalStateException(
					"A file repository " + repositoryId + " is already registered for " + existing.directory());
		}
		return store;
	}

	public void unregister(FileStore store) {
		stores.remove(store.id(), store);
	}

	public FileStore get(String repositoryId) {
		return repositoryId == null ? null : stores.get(repositoryId);
	}
}
