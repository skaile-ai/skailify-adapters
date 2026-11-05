/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.testing.filerepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.fennec.persistence.capabilities.PersistenceCapabilities;
import org.eclipse.fennec.persistence.tck.AbstractWritePathTCK;
import org.eclipse.fennec.persistence.tck.StoreProbe;

/**
 * The write-path conformance suite against the file repository. The store probe reads the
 * object files with a plain XMI resource, bypassing the backend.
 */
public class FileWritePathTckTest extends AbstractWritePathTCK {

	private Path directory;
	private FileStore store;

	@Override
	protected void setUpBackend(EPackage writePathPackage) throws IOException {
		directory = Files.createTempDirectory("filerepo-wp");
		store = new FileStore("wp", directory);
	}

	@Override
	protected void tearDownBackend() throws IOException {
		FilePersistenceTckTest.deleteRecursively(directory);
	}

	@Override
	protected ResourceSet createBackendResourceSet() {
		ResourceSet resourceSet = new ResourceSetImpl();
		models().forEach(model -> resourceSet.getPackageRegistry().put(model.getNsURI(), model));
		resourceSet.getResourceFactoryRegistry().getProtocolToFactoryMap().put(FileRepositoryConstants.URI_SCHEME,
				new FileRepositoryResourceFactory(id -> store.id().equals(id) ? store : null));
		return resourceSet;
	}

	@Override
	protected URI uriFor(String typeName) {
		return URI.createURI(FileRepositoryConstants.URI_SCHEME + "://wp/" + typeName);
	}

	@Override
	protected PersistenceCapabilities declaredCapabilities() {
		return FileRepositoryResource.CAPABILITIES;
	}

	@Override
	protected StoreProbe storeProbe() {
		return new StoreProbe() {

			@Override
			public Set<String> ids(EClass type) {
				Set<String> ids = new LinkedHashSet<>();
				for (EObject object : all()) {
					if (type.isSuperTypeOf(object.eClass())) {
						ids.add(EcoreUtil.getID(object));
					}
				}
				return ids;
			}

			@Override
			public Optional<String> reference(EClass type, String id, EReference reference) {
				List<String> targets = references(type, id, reference);
				return targets.isEmpty() ? Optional.empty() : Optional.of(targets.get(0));
			}

			@Override
			@SuppressWarnings("unchecked")
			public List<String> references(EClass type, String id, EReference reference) {
				EObject object = all().stream()
						.filter(o -> type.isSuperTypeOf(o.eClass()) && id.equals(EcoreUtil.getID(o)))
						.findFirst().orElse(null);
				List<String> result = new ArrayList<>();
				if (object == null) {
					return result;
				}
				Object value = object.eGet(reference, false);
				List<EObject> values = reference.isMany() ? (List<EObject>) value
						: value == null ? List.of() : List.of((EObject) value);
				for (EObject target : values) {
					URI uri = target.eIsProxy() ? ((InternalEObject) target).eProxyURI() : null;
					result.add(uri != null ? uri.fragment() : EcoreUtil.getID(target));
				}
				return result;
			}

			@Override
			public boolean storesContainedObjectsSeparately() {
				return false;
			}

			/** Every stored root object, read file by file with a plain XMI resource. */
			private List<EObject> all() {
				List<EObject> result = new ArrayList<>();
				try {
					for (Path file : store.allFiles()) {
						ResourceSet probeSet = new ResourceSetImpl();
						models().forEach(model -> probeSet.getPackageRegistry().put(model.getNsURI(), model));
						XMIResourceImpl resource = new XMIResourceImpl(URI.createFileURI(file.toString()));
						probeSet.getResources().add(resource);
						try (InputStream in = Files.newInputStream(file)) {
							resource.load(in, null);
						}
						result.addAll(resource.getContents());
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				return result;
			}
		};
	}
}
