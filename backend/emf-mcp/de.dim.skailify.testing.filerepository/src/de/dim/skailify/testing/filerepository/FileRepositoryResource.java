/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.testing.filerepository;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.common.util.WrappedException;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.util.InternalEList;
import org.eclipse.emf.ecore.xmi.XMIResource;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.fennec.persistence.Options;
import org.eclipse.fennec.persistence.capabilities.CommandCapabilitiesBuilder;
import org.eclipse.fennec.persistence.capabilities.PersistenceCapabilities;
import org.eclipse.fennec.persistence.capabilities.StoreCapabilitiesBuilder;
import org.eclipse.fennec.persistence.diagnostic.PersistenceDiagnostic;
import org.eclipse.fennec.persistence.helper.CompositeIds;
import org.eclipse.fennec.persistence.query.QueryException;
import org.eclipse.fennec.persistence.query.api.QueryResult;
import org.eclipse.fennec.persistence.query.api.QueryableResource;
import org.eclipse.fennec.persistence.query.memory.MemoryQueries;
import org.eclipse.fennec.persistence.query.memory.MemoryQueryProcessor;
import org.eclipse.fennec.persistence.query.support.PersistedQueries;
import org.eclipse.fennec.persistence.resource.PersistenceResource;
import org.eclipse.fennec.persistence.resource.StreamingResource;
import org.eclipse.fennec.model.query.Query;

/**
 * The collection resource {@code <scheme>://<storeId>/<EClassName>} of a file repository.
 * <p>
 * Objects are addressed by their EMF id as URI fragment. A keyed read
 * ({@link #getEObject(String)}) reads exactly one file; {@link #load(Map)}, queries and
 * streams read the whole collection. {@link #save(Map)} merges the current contents into the
 * store (one file per root object), {@link #delete(Map)} removes exactly the current contents
 * and refuses while other stored objects still reference them.
 * <p>
 * Upstream gap: resource part of the proposed {@code repository.file} flavour.
 */
public class FileRepositoryResource extends ResourceImpl
		implements PersistenceResource, QueryableResource, StreamingResource {

	/** What the file backend serves: in-memory queries, no commands, no store extras. */
	public static final PersistenceCapabilities CAPABILITIES = PersistenceCapabilities.of(
			new MemoryQueryProcessor().capabilities(), CommandCapabilitiesBuilder.create().build(),
			StoreCapabilitiesBuilder.create().build());

	/** URI segment of the scratch resource used to (de)serialize one object. */
	private static final String OBJECT_SEGMENT = "object";
	private static final String SOURCE = "de.dim.skailify.testing.filerepository";

	private final Function<String, FileStore> stores;
	private final Map<ActionType, Map<Object, Object>> defaultOptions = new EnumMap<>(ActionType.class);
	private boolean allLoaded;

	public FileRepositoryResource(URI uri, Function<String, FileStore> stores) {
		super(uri);
		this.stores = stores;
		// keyed reads load lazily: the resource set must never demand-load the whole collection
		this.isLoaded = true;
	}

	// ---- identity ----------------------------------------------------------------------

	@Override
	public String getURIFragment(EObject eObject) {
		String id = idOf(eObject);
		return nonNull(id) ? id : super.getURIFragment(eObject);
	}

	@Override
	public EObject getEObject(String fragment) {
		if (isNull(fragment)) {
			return null;
		}
		try {
			if (fragment.startsWith("/")) {
				ensureAllLoaded();
				return super.getEObject(fragment);
			}
			EObject found = findById(fragment);
			if (nonNull(found) || allLoaded) {
				return found;
			}
			found = loadOne(fragment);
			if (nonNull(found)) {
				return found;
			}
			// not a root object: it may be contained in one
			ensureAllLoaded();
			return findById(fragment);
		} catch (IOException e) {
			throw new WrappedException(e);
		}
	}

	/** The id fragment of an object: its id, or {@code k1=v1,k2=v2} for composite ids. */
	static String idOf(EObject object) {
		return CompositeIds.fragment(object);
	}

	/**
	 * The attached object (root or contained) with this id fragment. Composite fragments match
	 * regardless of the order of their pairs.
	 */
	private EObject findById(String fragment) {
		boolean composite = CompositeIds.isCompositeFragment(fragment);
		Set<String> pairs = composite ? Set.of(fragment.split(",")) : null;
		for (TreeIterator<EObject> it = getAllContents(); it.hasNext();) {
			EObject candidate = it.next();
			String id = idOf(candidate);
			if (isNull(id)) {
				continue;
			}
			if (composite ? pairs.equals(Set.of(id.split(","))) : id.equals(fragment)) {
				return candidate;
			}
		}
		return null;
	}

	// ---- load --------------------------------------------------------------------------

	/**
	 * An explicit load reads the whole collection. A <em>demand</em> load — the resource set
	 * resolving a URI through {@code getResource(uri, true)} passes its own load options — is
	 * a no-op: the object is then read by {@link #getEObject(String)} alone, otherwise
	 * resolving one object would attach (and a later delete would remove) the whole collection.
	 * <p>
	 * Upstream gap: EMF gives a resource no other way to tell the two apart.
	 */
	@Override
	public void load(Map<?, ?> options) throws IOException {
		ResourceSet resourceSet = getResourceSet();
		if (nonNull(options) && nonNull(resourceSet) && options == resourceSet.getLoadOptions()) {
			return;
		}
		ensureAllLoaded();
	}

	@Override
	protected void doUnload() {
		allLoaded = false;
		super.doUnload();
	}

	/** Reads every stored object of this collection that is not attached yet. */
	public void ensureAllLoaded() throws IOException {
		if (allLoaded) {
			return;
		}
		FileStore store = store();
		store.lock().readLock().lock();
		try {
			Set<String> present = new HashSet<>();
			for (EObject root : getContents()) {
				String id = idOf(root);
				if (nonNull(id)) {
					present.add(id);
				}
			}
			for (String id : store.ids(typeName())) {
				if (present.contains(id)) {
					continue;
				}
				try (InputStream in = store.read(typeName(), id)) {
					EObject root = isNull(in) ? null : parse(in);
					if (nonNull(root)) {
						getContents().add(root);
					}
				}
			}
			allLoaded = true;
		} finally {
			store.lock().readLock().unlock();
		}
	}

	private EObject loadOne(String id) throws IOException {
		FileStore store = store();
		store.lock().readLock().lock();
		try {
			Path file = locate(store, id);
			if (isNull(file)) {
				return null;
			}
			try (InputStream in = Files.newInputStream(file)) {
				EObject root = parse(in);
				if (nonNull(root)) {
					getContents().add(root);
				}
				return root;
			}
		} finally {
			store.lock().readLock().unlock();
		}
	}

	/**
	 * The file of a root object: in this collection's directory, or — for an object saved
	 * through a supertype collection — in the directory of its concrete type.
	 */
	private Path locate(FileStore store, String id) throws IOException {
		Path own = store.file(typeName(), id);
		if (Files.isRegularFile(own)) {
			return own;
		}
		String fileName = FileStore.encode(id) + FileStore.EXTENSION;
		for (Path file : store.allFiles()) {
			if (file.getFileName().toString().equals(fileName)) {
				return file;
			}
		}
		return null;
	}

	private EObject parse(InputStream in) throws IOException {
		XMIResource scratch = new XMIResourceImpl(uri.appendSegment(OBJECT_SEGMENT));
		ResourceSet resourceSet = getResourceSet();
		if (nonNull(resourceSet)) {
			// borrow the set's package registry; relative hrefs resolve against the scratch URI
			resourceSet.getResources().add(scratch);
		}
		try {
			scratch.load(in, options(ActionType.LOAD, null));
			return scratch.getContents().isEmpty() ? null : scratch.getContents().get(0);
		} catch (IOException | RuntimeException e) {
			throw fail("Cannot read a stored object of " + uri + ": " + e.getMessage(), e);
		} finally {
			if (nonNull(resourceSet)) {
				resourceSet.getResources().remove(scratch);
			}
		}
	}

	// ---- save --------------------------------------------------------------------------

	@Override
	public void save(Map<?, ?> options) throws IOException {
		FileStore store = store();
		List<EObject> roots = new ArrayList<>(getContents());
		assignIds(roots);
		refuseUnreferenceableTargets(roots);
		Map<Object, Object> saveOptions = options(ActionType.SAVE, options);
		store.lock().writeLock().lock();
		try {
			for (EObject root : roots) {
				String id = idOf(root);
				if (isNull(id)) {
					throw fail("Cannot save an object without id: " + root, null);
				}
				// stored in this collection (an object read through another collection is found via locate())
				store.write(typeName(), id, out -> serialize(root, out, saveOptions));
			}
		} finally {
			store.lock().writeLock().unlock();
		}
		setModified(false);
	}

	/**
	 * A reference is stored as {@code <collection>#<id>}: a target in no resource or without
	 * an id would be stored as a dangling or path-based href, so the save is refused before
	 * anything is written.
	 */
	private void refuseUnreferenceableTargets(List<EObject> roots) throws IOException {
		for (EObject root : roots) {
			for (EObject object : withContents(root)) {
				for (EReference reference : storedReferences(object)) {
					for (EObject target : values(object, reference)) {
						if (target.eIsProxy()) {
							continue;
						}
						if (isNull(target.eResource())) {
							throw fail("Cannot save " + object.eClass().getName() + "." + reference.getName()
									+ ": the referenced object is in no resource", null);
						}
						if (isNull(idOf(target)) && target.eResource() != this) {
							throw fail("Cannot save " + object.eClass().getName() + "." + reference.getName()
									+ ": the referenced object has no id", null);
						}
					}
				}
			}
		}
	}

	private void serialize(EObject root, OutputStream out, Map<Object, Object> options) throws IOException {
		// The object itself is serialized, not a copy: EcoreUtil.copy drops references with an
		// eOpposite to objects outside the copied tree. It moves into the scratch resource for
		// the save and back to its position afterwards (a root has no container to notify).
		XMIResource scratch = new XMIResourceImpl(uri.appendSegment(OBJECT_SEGMENT));
		int index = getContents().indexOf(root);
		scratch.getContents().add(root);
		try {
			scratch.save(out, options);
		} finally {
			if (index >= 0) {
				getContents().add(Math.min(index, getContents().size()), root);
			} else {
				getContents().add(root);
			}
		}
	}

	@Override
	public void assignIds(Collection<? extends EObject> objects) {
		for (EObject object : objects) {
			for (EObject each : withContents(object)) {
				assignId(each);
			}
		}
	}

	private static void assignId(EObject object) {
		EAttribute idAttribute = object.eClass().getEIDAttribute();
		if (nonNull(idAttribute) && !object.eIsSet(idAttribute) && idAttribute.isChangeable()
				&& idAttribute.getEAttributeType().getInstanceClass() == String.class) {
			object.eSet(idAttribute, UUID.randomUUID().toString());
		}
	}

	// ---- delete ------------------------------------------------------------------------

	/**
	 * Deletes exactly the current contents (with their contained objects). Stored objects that
	 * still reference a deleted one decide, per reference:
	 * <ul>
	 * <li>a many-valued reference with an opposite is a link: it is removed from the referrer;</li>
	 * <li>any other reference is a dependency: the delete is refused and nothing changes.</li>
	 * </ul>
	 * An object without a file is nothing to delete: a warning {@code CODE_NOT_FOUND}, the others
	 * are deleted; with {@code OPTION_DELETE_REQUIRE_EXISTING} it is an error and nothing changes.
	 */
	@Override
	public void delete(Map<?, ?> options) throws IOException {
		Map<Object, Object> deleteOptions = options(ActionType.DELETE, options);
		if (Options.isDeleteIgnoreReferences(deleteOptions) || Options.isDeleteClearReferences(deleteOptions)) {
			throw fail("The file repository serves neither DELETE_IGNORE_REFERENCES nor DELETE_CLEAR_REFERENCES",
					null);
		}
		FileStore store = store();
		List<EObject> roots = new ArrayList<>(getContents());
		Set<String> ids = new HashSet<>();
		Set<String> rootIds = new LinkedHashSet<>();
		for (EObject root : roots) {
			String rootId = idOf(root);
			if (nonNull(rootId)) {
				rootIds.add(rootId);
			}
			for (EObject each : withContents(root)) {
				String id = idOf(each);
				if (nonNull(id)) {
					ids.add(id);
				}
			}
		}
		Map<Object, Object> saveOptions = options(ActionType.SAVE, null);
		store.lock().writeLock().lock();
		try {
			Set<Path> deleted = new HashSet<>();
			List<String> missing = new ArrayList<>();
			for (String id : rootIds) {
				Path file = locate(store, id);
				if (nonNull(file)) {
					deleted.add(file);
				} else {
					missing.add(id);
				}
			}
			if (!missing.isEmpty() && Options.isDeleteRequireExisting(deleteOptions)) {
				String message = "Cannot delete, not stored: " + String.join(", ", missing);
				getErrors().add(PersistenceDiagnostic.error(PersistenceDiagnostic.CODE_NOT_FOUND, SOURCE, message,
						uri.appendFragment(missing.get(0)), null));
				throw new IOException(message);
			}
			for (String id : missing) {
				getWarnings().add(PersistenceDiagnostic.warning(PersistenceDiagnostic.CODE_NOT_FOUND, SOURCE,
						"Nothing to delete, not stored: " + id, uri.appendFragment(id)));
			}
			List<Referrer> referrers = referrers(store, ids, deleted);
			for (Referrer referrer : referrers) {
				referrer.unlink();
				store.write(referrer.typeName, referrer.objectId, out -> referrer.resource.save(out, saveOptions));
			}
			for (Path file : deleted) {
				Files.deleteIfExists(file);
			}
		} finally {
			store.lock().writeLock().unlock();
		}
		getContents().removeAll(roots);
	}

	/**
	 * Finds the stored objects referencing one of the ids. Refuses — before anything is changed —
	 * if one of those references is a dependency; returns the referrers whose links to remove.
	 * Files without the textual {@code #<id>"} href are skipped without parsing.
	 */
	private List<Referrer> referrers(FileStore store, Set<String> ids, Set<Path> deleted) throws IOException {
		List<Referrer> result = new ArrayList<>();
		for (Path file : store.allFiles()) {
			if (deleted.contains(file)) {
				continue;
			}
			String content = Files.readString(file, StandardCharsets.UTF_8);
			if (ids.stream().noneMatch(id -> content.contains("#" + id + "\""))) {
				continue;
			}
			String typeName = file.getParent().getFileName().toString();
			String fileName = file.getFileName().toString();
			String objectId = FileStore.decode(fileName.substring(0, fileName.length() - FileStore.EXTENSION.length()));
			Referrer referrer = new Referrer(typeName, objectId, readRaw(typeName, file));
			for (EObject object : referrer.objects()) {
				for (EReference reference : storedReferences(object)) {
					for (EObject target : values(object, reference)) {
						String targetId = target.eIsProxy() ? ((InternalEObject) target).eProxyURI().fragment()
								: idOf(target);
						if (isNull(targetId) || !ids.contains(targetId)) {
							continue;
						}
						if (reference.isMany() && nonNull(reference.getEOpposite())) {
							referrer.links.add(new Link(object, reference, target));
						} else {
							String message = "Cannot delete " + targetId + ": " + typeName + "#" + objectId + "."
									+ reference.getName() + " still references it";
							getErrors().add(PersistenceDiagnostic.error(PersistenceDiagnostic.CODE_REFERENTIAL_INTEGRITY,
									SOURCE, message, uri.appendFragment(targetId), null));
							throw new IOException(message);
						}
					}
				}
			}
			if (!referrer.links.isEmpty()) {
				result.add(referrer);
			}
		}
		return result;
	}

	/** Reads a stored file as plain XMI (proxies stay unresolved) at its canonical scratch URI. */
	private XMIResource readRaw(String typeName, Path file) throws IOException {
		ResourceSet raw = new ResourceSetImpl();
		ResourceSet own = getResourceSet();
		if (nonNull(own)) {
			raw.setPackageRegistry(own.getPackageRegistry());
		}
		XMIResource resource = new XMIResourceImpl(uri.trimSegments(1).appendSegment(typeName).appendSegment(OBJECT_SEGMENT));
		raw.getResources().add(resource);
		try (InputStream in = Files.newInputStream(file)) {
			resource.load(in, options(ActionType.LOAD, null));
		}
		return resource;
	}

	private record Link(EObject owner, EReference reference, EObject target) {
	}

	private static final class Referrer {
		final String typeName;
		final String objectId;
		final XMIResource resource;
		final List<Link> links = new ArrayList<>();

		Referrer(String typeName, String objectId, XMIResource resource) {
			this.typeName = typeName;
			this.objectId = objectId;
			this.resource = resource;
		}

		List<EObject> objects() {
			List<EObject> result = new ArrayList<>();
			resource.getContents().forEach(root -> result.addAll(withContents(root)));
			return result;
		}

		@SuppressWarnings("unchecked")
		void unlink() {
			for (Link link : links) {
				// basicRemove: the opposite end is being deleted, there is nothing to notify
				((InternalEList<EObject>) link.owner().eGet(link.reference(), false)).basicRemove(link.target(), null);
			}
		}
	}

	private static List<EObject> withContents(EObject root) {
		List<EObject> result = new ArrayList<>();
		result.add(root);
		for (TreeIterator<EObject> it = root.eAllContents(); it.hasNext();) {
			result.add(it.next());
		}
		return result;
	}

	/** The non-containment references whose values are part of the stored state. */
	private static List<EReference> storedReferences(EObject object) {
		List<EReference> result = new ArrayList<>();
		for (EReference reference : object.eClass().getEAllReferences()) {
			if (!reference.isContainment() && !reference.isContainer() && !reference.isTransient()
					&& !reference.isDerived() && object.eIsSet(reference)) {
				result.add(reference);
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<EObject> values(EObject object, EReference reference) {
		Object value = object.eGet(reference, false);
		if (reference.isMany()) {
			return new ArrayList<>((List<EObject>) value);
		}
		return isNull(value) ? List.of() : List.of((EObject) value);
	}

	// ---- count / exist / stream / query ------------------------------------------------

	@Override
	public long count() {
		return count(null);
	}

	@Override
	public long count(Map<?, ?> options) {
		try {
			return store().ids(typeName()).size();
		} catch (IOException e) {
			throw new WrappedException(e);
		}
	}

	@Override
	public boolean exist() {
		return exist(null);
	}

	@Override
	public boolean exist(Map<?, ?> options) {
		return count(options) > 0;
	}

	@Override
	public PersistenceCapabilities capabilities() {
		return CAPABILITIES;
	}

	@Override
	public void updateDefaultOptions(Map<Object, Object> options, ActionType... types) {
		if (isNull(options)) {
			return;
		}
		ActionType[] targets = types.length == 0 ? ActionType.values() : types;
		for (ActionType type : targets) {
			defaultOptions.computeIfAbsent(type, t -> new HashMap<>()).putAll(options);
		}
	}

	@Override
	public Stream<EObject> stream() throws IOException {
		return stream(null);
	}

	@Override
	public Stream<EObject> stream(Map<?, ?> options) throws IOException {
		ensureAllLoaded();
		return new ArrayList<>(getContents()).stream();
	}

	@Override
	public QueryResult query(Query query) throws IOException {
		return query(query, null, null);
	}

	@Override
	public QueryResult query(Query query, Map<String, Object> parameters, Map<?, ?> options) throws IOException {
		if (isNull(query) || isNull(query.getFrom())) {
			throw fail("Query has no root type (from)", null);
		}
		try {
			String catalogName = PersistedQueries.catalogName(query);
			if (nonNull(catalogName)) {
				FileStore store = store();
				String xmi = PersistedQueries.toXmi(query);
				store.lock().writeLock().lock();
				try {
					store.writeQuery(catalogName, xmi);
				} finally {
					store.lock().writeLock().unlock();
				}
			}
			// a count (exist by id) reads the files aside, without loading the objects into the resource
			List<EObject> candidates = query.isCountOnly() ? storedCandidates(query.getFrom())
					: candidates(query.getFrom());
			return MemoryQueries.execute(query, candidates, parameters);
		} catch (QueryException e) {
			throw fail(e.getMessage(), e);
		}
	}

	/** Runs a query from the store's catalog ({@code <directory>/.queries/<name>.xmi}). */
	@Override
	public QueryResult query(String name, Map<String, Object> parameters, Map<?, ?> options) throws IOException {
		FileStore store = store();
		String xmi;
		store.lock().readLock().lock();
		try {
			xmi = store.readQuery(name);
		} finally {
			store.lock().readLock().unlock();
		}
		if (isNull(xmi)) {
			throw fail("No query named '" + name + "' in the catalog of " + uri.authority(), null);
		}
		ResourceSet resourceSet = getResourceSet();
		EPackage.Registry registry = nonNull(resourceSet) ? resourceSet.getPackageRegistry() : EPackage.Registry.INSTANCE;
		try {
			return query(PersistedQueries.forExecution(PersistedQueries.fromXmi(name, xmi, registry)), parameters,
					options);
		} catch (QueryException e) {
			throw fail("Stored query '" + name + "' cannot be read: " + e.getMessage(), e);
		}
	}

	/**
	 * All stored instances of the type: this collection plus the collections of its subtypes
	 * within the same EPackage (subtypes in other packages are not discovered).
	 */
	private List<EObject> candidates(EClass from) throws IOException {
		Set<EObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<EObject> result = new ArrayList<>();
		collect(this, from, seen, result);
		ResourceSet resourceSet = getResourceSet();
		if (nonNull(resourceSet) && nonNull(from.getEPackage())) {
			URI base = uri.trimSegments(1);
			for (EClassifier classifier : from.getEPackage().getEClassifiers()) {
				if (classifier instanceof EClass sub && sub != from && from.isSuperTypeOf(sub)
						&& !sub.getName().equals(typeName())) {
					URI subUri = base.appendSegment(sub.getName());
					Resource resource = resourceSet.getResource(subUri, false);
					if (isNull(resource)) {
						resource = resourceSet.createResource(subUri);
					}
					if (resource instanceof FileRepositoryResource fileResource) {
						collect(fileResource, from, seen, result);
					}
				}
			}
		}
		return result;
	}

	/** All stored instances of the type like {@link #candidates(EClass)}, read aside: nothing is attached. */
	private List<EObject> storedCandidates(EClass from) throws IOException {
		FileStore store = store();
		List<String> typeNames = new ArrayList<>();
		typeNames.add(typeName());
		if (nonNull(from.getEPackage())) {
			for (EClassifier classifier : from.getEPackage().getEClassifiers()) {
				if (classifier instanceof EClass sub && sub != from && from.isSuperTypeOf(sub)
						&& !sub.getName().equals(typeName())) {
					typeNames.add(sub.getName());
				}
			}
		}
		List<EObject> result = new ArrayList<>();
		store.lock().readLock().lock();
		try {
			for (String name : typeNames) {
				for (String id : store.ids(name)) {
					for (EObject root : readRaw(name, store.file(name, id)).getContents()) {
						if (from.isInstance(root)) {
							result.add(root);
						}
					}
				}
			}
		} finally {
			store.lock().readLock().unlock();
		}
		return result;
	}

	private static void collect(FileRepositoryResource resource, EClass from, Set<EObject> seen, List<EObject> result)
			throws IOException {
		resource.ensureAllLoaded();
		for (EObject root : resource.getContents()) {
			if (from.isInstance(root) && seen.add(root)) {
				result.add(root);
			}
		}
	}

	@Override
	public void close() {
		unload();
	}

	// ---- helpers -----------------------------------------------------------------------

	private FileStore store() throws IOException {
		FileStore store = stores.apply(uri.authority());
		if (isNull(store)) {
			throw fail("No file repository store '" + uri.authority() + "' is available for " + uri, null);
		}
		return store;
	}

	private String typeName() {
		return uri.lastSegment();
	}

	private Map<Object, Object> options(ActionType type, Map<?, ?> options) {
		Map<Object, Object> result = new HashMap<>();
		result.put(XMLResource.OPTION_ENCODING, "UTF-8");
		result.put(XMLResource.OPTION_SAVE_TYPE_INFORMATION, Boolean.FALSE);
		Map<Object, Object> defaults = defaultOptions.get(type);
		if (nonNull(defaults)) {
			result.putAll(defaults);
		}
		if (nonNull(options)) {
			result.putAll(options);
		}
		return result;
	}

	private IOException fail(String message, Throwable cause) {
		getErrors().add(PersistenceDiagnostic.error(SOURCE, message, uri));
		return isNull(cause) ? new IOException(message) : new IOException(message, cause);
	}
}
