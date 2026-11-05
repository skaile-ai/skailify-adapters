/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.testing.filerepository;

import java.nio.file.Path;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.persistence.query.memory.MemoryQueryProcessor;
import org.eclipse.fennec.persistence.repository.spi.AbstractRepository;
import org.eclipse.fennec.persistence.repository.spi.AbstractRepositoryComponent;
import org.osgi.framework.BundleContext;

/**
 * One registered file repository: its store plus the repository facade services (prototype
 * scope, base URI {@code filerepo://<repositoryId>}). Used by the configuration-admin
 * component and by other configurators (e.g. a Data Atlas input) alike.
 */
public final class FileRepositoryRegistration extends AbstractRepositoryComponent implements AutoCloseable {

	private final String repositoryId;
	private final URI baseUri;
	private final ResourceSetFactory resourceSetFactory;
	private final FileStoreRegistry registry;
	private final FileStore store;

	private FileRepositoryRegistration(String repositoryId, ResourceSetFactory resourceSetFactory,
			FileStoreRegistry registry, FileStore store) {
		this.repositoryId = repositoryId;
		this.baseUri = URI.createURI(FileRepositoryConstants.URI_SCHEME + "://" + repositoryId);
		this.resourceSetFactory = resourceSetFactory;
		this.registry = registry;
		this.store = store;
	}

	/**
	 * Registers a file repository over the directory.
	 * <p>
	 * Upstream gap: the SPI's {@code register} takes no additional service properties, so a
	 * configurator cannot tag the repository (e.g. with the Data Atlas name).
	 */
	public static FileRepositoryRegistration open(BundleContext context, ResourceSetFactory resourceSetFactory,
			FileStoreRegistry registry, String repositoryId, Path directory, boolean readOnly) {
		if (repositoryId == null || repositoryId.isBlank()) {
			throw new IllegalArgumentException("repositoryId must be set");
		}
		if (directory == null) {
			throw new IllegalArgumentException("directory must be set for file repository " + repositoryId);
		}
		FileStore store = registry.register(repositoryId, directory);
		FileRepositoryRegistration registration = new FileRepositoryRegistration(repositoryId, resourceSetFactory,
				registry, store);
		try {
			registration.register(context, repositoryId, registration.baseUri, readOnly);
		} catch (RuntimeException e) {
			registry.unregister(store);
			throw e;
		}
		return registration;
	}

	public FileStore store() {
		return store;
	}

	public URI baseUri() {
		return baseUri;
	}

	@Override
	public void close() {
		unregister();
		registry.unregister(store);
	}

	@Override
	protected AbstractRepository createRepository() {
		return new AbstractRepository(repositoryId, baseUri, resourceSetFactory::createResourceSet,
				new MemoryQueryProcessor(), Map.of(), Map.of()) {
		};
	}
}
