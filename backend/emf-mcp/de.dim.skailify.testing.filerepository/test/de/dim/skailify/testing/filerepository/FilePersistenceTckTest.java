/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.testing.filerepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.fennec.persistence.capabilities.PersistenceCapabilities;
import org.eclipse.fennec.persistence.tck.AbstractPersistenceTCK;

/**
 * The Fennec persistence TCK against the file repository (String-id model: the file store
 * generates UUIDs only).
 */
class FilePersistenceTckTest extends AbstractPersistenceTCK {

	private Path directory;
	private FileStore store;

	@Override
	protected String tckModelPath() {
		return "tck-string.ecore";
	}

	@Override
	protected void setUpBackend(EPackage tckPackage) throws IOException {
		directory = Files.createTempDirectory("filerepo-tck");
		store = new FileStore("tck", directory);
	}

	@Override
	protected void tearDownBackend() throws IOException {
		deleteRecursively(directory);
	}

	@Override
	protected ResourceSet createBackendResourceSet() {
		ResourceSet resourceSet = new ResourceSetImpl();
		resourceSet.getPackageRegistry().put(tckPackage.getNsURI(), tckPackage);
		resourceSet.getResourceFactoryRegistry().getProtocolToFactoryMap().put(FileRepositoryConstants.URI_SCHEME,
				new FileRepositoryResourceFactory(id -> store.id().equals(id) ? store : null));
		resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
		return resourceSet;
	}

	@Override
	protected URI uriFor(String typeName) {
		return URI.createURI(FileRepositoryConstants.URI_SCHEME + "://tck/" + typeName);
	}

	@Override
	protected PersistenceCapabilities declaredCapabilities() {
		return FileRepositoryResource.CAPABILITIES;
	}

	/** A stored file that is not XMI cannot be read; the resource reports it. */
	@Override
	protected Resource provokeLoadDiagnostic() throws Exception {
		Path broken = store.file(personClass.getName(), "broken");
		Files.createDirectories(broken.getParent());
		Files.writeString(broken, "this is not XMI", StandardCharsets.UTF_8);
		Resource resource = createBackendResourceSet().createResource(uriFor(personClass.getName()));
		try {
			resource.load(null);
		} catch (IOException | RuntimeException signalled) {
			// conforming: the diagnostics stay on the resource
		}
		return resource;
	}

	static void deleteRecursively(Path directory) throws IOException {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
