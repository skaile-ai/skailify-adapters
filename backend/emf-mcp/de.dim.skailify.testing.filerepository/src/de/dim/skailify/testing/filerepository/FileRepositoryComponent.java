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

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * One factory configuration = one file repository over one directory; registers the
 * repository facade (prototype scope) with base URI {@code filerepo://<repositoryId>}.
 */
@Component(name = FileRepositoryConstants.FACTORY_PID, configurationPolicy = ConfigurationPolicy.REQUIRE,
		service = {})
@Designate(ocd = FileRepositoryComponent.Config.class, factory = true)
public class FileRepositoryComponent {

	@ObjectClassDefinition(name = "Fennec File Repository",
			description = "Writable repository storing one XMI file per object: <directory>/<EClass>/<id>.xmi")
	public @interface Config {

		@AttributeDefinition(description = "Stable id, published as persistence.repository.id and used as URI authority")
		String repositoryId();

		@AttributeDefinition(description = "Directory holding the object files")
		String directory();

		@AttributeDefinition(description = "Withhold WriteRepository/Repository from the registry")
		boolean readOnly() default false;
	}

	private final FileRepositoryRegistration registration;

	/**
	 * The resource factory reference only orders activation: the repository must not appear
	 * before ResourceSets know the {@code filerepo} protocol.
	 */
	@Activate
	public FileRepositoryComponent(BundleContext context, Config config,
			@Reference ResourceSetFactory resourceSetFactory, @Reference FileStoreRegistry registry,
			@Reference(target = "(emf.protocol=" + FileRepositoryConstants.URI_SCHEME + ")") Resource.Factory factory) {
		if (config.directory() == null || config.directory().isBlank()) {
			throw new IllegalArgumentException("directory must be set for file repository " + config.repositoryId());
		}
		registration = FileRepositoryRegistration.open(context, resourceSetFactory, registry, config.repositoryId(),
				Path.of(config.directory()), config.readOnly());
	}

	@Deactivate
	public void deactivate() {
		registration.close();
	}
}
