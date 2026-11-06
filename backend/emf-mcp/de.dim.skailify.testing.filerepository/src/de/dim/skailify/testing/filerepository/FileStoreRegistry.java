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
