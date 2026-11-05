/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.testing.filerepository;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.fennec.emf.osgi.annotation.ConfiguratorType;
import org.eclipse.fennec.emf.osgi.annotation.provide.EMFConfigurator;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Registers the {@code filerepo} protocol with every Fennec ResourceSet.
 */
@Component(name = "FileRepositoryResourceFactory", service = Resource.Factory.class, immediate = true)
@EMFConfigurator(configuratorName = FileRepositoryConstants.URI_SCHEME,
		configuratorType = ConfiguratorType.RESOURCE_FACTORY, protocol = FileRepositoryConstants.URI_SCHEME)
public class FileRepositoryResourceFactoryComponent implements Resource.Factory {

	private final FileRepositoryResourceFactory delegate;

	@Activate
	public FileRepositoryResourceFactoryComponent(@Reference FileStoreRegistry registry) {
		this.delegate = new FileRepositoryResourceFactory(registry::get);
	}

	@Override
	public Resource createResource(URI uri) {
		return delegate.createResource(uri);
	}
}
