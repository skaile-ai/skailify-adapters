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
package de.dim.skailify.emf;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.util.FeatureMap;

/**
 * Objects of a model as JSON-like maps (strings, numbers, booleans, lists, maps), and back.
 * <ul>
 * <li>Every object carries {@code "type"} (its EClass name) and its ID attribute, then each
 * feature with a value under its name (attributes at their default included).</li>
 * <li>Attributes: numbers and booleans as such, enums by literal, other data types (dates, ...)
 * in the string form of their EDataType. Decimals are accepted as numbers or as strings; strings
 * keep every digit.</li>
 * <li>Contained objects are nested maps. On an update, a nested map with the {@code id} of an
 * existing contained object changes that object, one without creates a new one; contained
 * objects left out of a list are removed.</li>
 * <li>Other references are {@code {"type": "Customer", "id": "..."}} (written with a
 * {@code "label"}). A contained object without id is addressed by its path from the root object,
 * {@code {"path": "lineItems/0"}}. A bare id string is accepted when the reference type is
 * concrete.</li>
 * </ul>
 * Values a request gets wrong are collected and thrown together as an
 * {@link InvalidRequestException}, so an agent can fix them in one go.
 */
public final class ObjectJson {

	/** Finds a stored object by type (possibly abstract) and id; answers null when there is none. */
	@FunctionalInterface
	public interface Lookup {
		EObject find(EClass type, String id) throws IOException;
	}

	public static final String TYPE = "type";
	public static final String ID = "id";
	public static final String PATH = "path";
	public static final String LABEL = "label";

	private final ModelTypes types;
	private final List<String> labelFeatures;

	/**
	 * @param labelFeatures the attributes whose value labels a reference, first set one wins
	 *            (e.g. {@code name})
	 */
	public ObjectJson(ModelTypes types, List<String> labelFeatures) {
		this.types = types;
		this.labelFeatures = List.copyOf(labelFeatures);
	}

	// ---------------------------------------------------------------- write

	/** The object with everything it contains. */
	public Map<String, Object> write(EObject object) {
		return write(object, EcoreUtil.getRootContainer(object));
	}

	private Map<String, Object> write(EObject object, EObject root) {
		Map<String, Object> json = new LinkedHashMap<>();
		EClass eClass = object.eClass();
		json.put(TYPE, eClass.getName());
		EAttribute idAttribute = eClass.getEIDAttribute();
		if (idAttribute != null && object.eIsSet(idAttribute)) {
			json.put(idAttribute.getName(), attributeValue(idAttribute, object.eGet(idAttribute)));
		}
		for (EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
			if (feature == idAttribute || !visible(feature)) {
				continue;
			}
			// an attribute at its default (an enum's first literal, false, 0) is not set, but has
			// that value: it is written, only null and empty values are left out
			if (!object.eIsSet(feature) && (feature instanceof EReference || feature.isMany()
					|| object.eGet(feature) == null)) {
				continue;
			}
			Object value = object.eGet(feature, true);
			if (feature instanceof EAttribute attribute) {
				json.put(feature.getName(), feature.isMany()
						? ((Collection<?>) value).stream().map(v -> attributeValue(attribute, v)).toList()
						: attributeValue(attribute, value));
			} else {
				EReference reference = (EReference) feature;
				if (feature.isMany()) {
					List<Object> values = new ArrayList<>();
					for (Object target : (Collection<?>) value) {
						values.add(referenceValue(reference, (EObject) target, root));
					}
					json.put(feature.getName(), values);
				} else {
					json.put(feature.getName(), referenceValue(reference, (EObject) value, root));
				}
			}
		}
		return json;
	}

	/**
	 * Whether a feature is read and written: no derived, transient or container features, none
	 * the model hides from agents ({@link SkailifyAnnotations#hidden(EStructuralFeature)})
	 */
	public static boolean visible(EStructuralFeature feature) {
		if (feature.isDerived() || feature.isTransient() || feature.isVolatile() || SkailifyAnnotations.hidden(feature)) {
			return false;
		}
		if (feature instanceof EReference reference && reference.isContainer()) {
			return false;
		}
		return !FeatureMap.Entry.class.equals(feature.getEType().getInstanceClass());
	}

	private Object referenceValue(EReference reference, EObject target, EObject root) {
		return reference.isContainment() ? write(target, root) : reference(target, root);
	}

	/** A reference to the object: type and id (or path), and its label */
	public Map<String, Object> reference(EObject target, EObject root) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put(TYPE, target.eClass().getName());
		if (target.eIsProxy()) {
			json.put("unresolved", EcoreUtil.getURI(target).toString());
			return json;
		}
		String id = EcoreUtil.getID(target);
		if (id != null) {
			json.put(ID, id);
		} else if (root != null && EcoreUtil.isAncestor(root, target)) {
			json.put(PATH, path(root, target));
		}
		for (String name : labelFeatures) {
			EStructuralFeature label = target.eClass().getEStructuralFeature(name);
			if (label instanceof EAttribute && !label.isMany() && visible(label) && target.eIsSet(label)) {
				json.put(LABEL, String.valueOf(target.eGet(label)));
				break;
			}
		}
		return json;
	}

	static String path(EObject root, EObject object) {
		List<String> segments = new ArrayList<>();
		for (EObject current = object; current != root && current != null; current = current.eContainer()) {
			EReference feature = current.eContainmentFeature();
			if (feature.isMany()) {
				segments.add(0, String.valueOf(((List<?>) current.eContainer().eGet(feature)).indexOf(current)));
			}
			segments.add(0, feature.getName());
		}
		return String.join("/", segments);
	}

	static Object attributeValue(EAttribute attribute, Object value) {
		if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigDecimal
				|| value instanceof BigInteger || value instanceof Integer || value instanceof Long
				|| value instanceof Short || value instanceof Byte) {
			return value;
		}
		if ((value instanceof Double d && Double.isFinite(d)) || (value instanceof Float f && Float.isFinite(f))) {
			return value;
		}
		if (value instanceof Enumerator enumerator) {
			return enumerator.getLiteral();
		}
		return EcoreUtil.convertToString(attribute.getEAttributeType(), value);
	}

	// ---------------------------------------------------------------- read

	/**
	 * A new object of the class from the fields. A root object gets no {@code id}: the
	 * repository assigns it on save.
	 */
	public EObject create(EClass eClass, Map<String, ?> fields, Lookup lookup) throws InvalidRequestException {
		Build build = new Build(lookup);
		if (eClass.isAbstract() || eClass.isInterface()) {
			throw new InvalidRequestException(TYPE, eClass.getName() + " is abstract, use one of "
					+ types.concreteSubtypes(eClass).stream().map(EClass::getName).toList());
		}
		EObject object = EcoreUtil.create(eClass);
		EAttribute idAttribute = eClass.getEIDAttribute();
		if (idAttribute != null && fields.get(idAttribute.getName()) != null) {
			build.errors.add(new FieldError(idAttribute.getName(), "is assigned when the object is created; leave it out"));
		}
		build.apply(object, fields, "", Set.of(idAttribute == null ? "" : idAttribute.getName()));
		build.finish(object);
		return object;
	}

	/**
	 * Changes the fields given, leaves the others alone; a field set to null is unset. The id
	 * cannot change.
	 */
	public void update(EObject object, Map<String, ?> fields, Lookup lookup) throws InvalidRequestException {
		Build build = new Build(lookup);
		EAttribute idAttribute = object.eClass().getEIDAttribute();
		if (idAttribute != null && fields.containsKey(idAttribute.getName())) {
			Object given = fields.get(idAttribute.getName());
			if (given != null && !String.valueOf(given).equals(EcoreUtil.getID(object))) {
				build.errors.add(new FieldError(idAttribute.getName(), "cannot be changed"));
			}
		}
		build.apply(object, fields, "", Set.of(idAttribute == null ? "" : idAttribute.getName()));
		build.finish(object);
	}

	/** One read of a request: the errors so far and the references resolved at the end. */
	private final class Build {

		final Lookup lookup;
		final List<FieldError> errors = new ArrayList<>();
		final List<PendingReference> pending = new ArrayList<>();

		Build(Lookup lookup) {
			this.lookup = lookup;
		}

		void apply(EObject target, Map<String, ?> fields, String at, Set<String> skip) {
			EClass eClass = target.eClass();
			for (Map.Entry<String, ?> entry : fields.entrySet()) {
				String key = entry.getKey();
				String field = at.isEmpty() ? key : at + "." + key;
				if (TYPE.equals(key)) {
					if (entry.getValue() != null && !eClass.getName().equals(entry.getValue())) {
						errors.add(new FieldError(field, "is " + eClass.getName() + ", not " + entry.getValue()));
					}
					continue;
				}
				if (skip.contains(key)) {
					continue;
				}
				EStructuralFeature feature = eClass.getEStructuralFeature(key);
				if (feature == null || !visible(feature)) {
					errors.add(new FieldError(field, "unknown field of " + eClass.getName() + "; fields: "
							+ eClass.getEAllStructuralFeatures().stream().filter(ObjectJson::visible)
									.map(EStructuralFeature::getName).toList()));
					continue;
				}
				if (!feature.isChangeable()) {
					errors.add(new FieldError(field, "cannot be changed"));
					continue;
				}
				Object value = entry.getValue();
				if (value == null) {
					target.eUnset(feature);
				} else if (feature instanceof EAttribute attribute) {
					setAttribute(target, attribute, value, field);
				} else if (((EReference) feature).isContainment()) {
					setContainment(target, (EReference) feature, value, field);
				} else {
					setReference(target, (EReference) feature, value, field);
				}
			}
		}

		void setAttribute(EObject target, EAttribute attribute, Object value, String field) {
			if (attribute.isMany()) {
				List<Object> values = new ArrayList<>();
				int i = 0;
				for (Object item : asList(value)) {
					values.add(convert(attribute.getEAttributeType(), item, field + "[" + i++ + "]"));
				}
				if (!values.contains(INVALID)) {
					target.eSet(attribute, values);
				}
			} else {
				Object converted = convert(attribute.getEAttributeType(), value, field);
				if (converted != INVALID) {
					target.eSet(attribute, converted);
				}
			}
		}

		Object convert(EDataType type, Object value, String field) {
			try {
				return Values.convert(type, value);
			} catch (IllegalArgumentException e) {
				errors.add(new FieldError(field, e.getMessage()));
				return INVALID;
			}
		}

		void setContainment(EObject target, EReference reference, Object value, String field) {
			if (reference.isMany()) {
				@SuppressWarnings("unchecked")
				List<EObject> existing = (List<EObject>) target.eGet(reference);
				List<EObject> children = new ArrayList<>();
				int i = 0;
				for (Object item : asList(value)) {
					EObject child = child(reference, item, existing, field + "[" + i++ + "]");
					if (child != null) {
						children.add(child);
					}
				}
				target.eSet(reference, children);
			} else {
				EObject current = (EObject) target.eGet(reference);
				EObject child = child(reference, value, current == null ? List.of() : List.of(current), field);
				if (child != null) {
					target.eSet(reference, child);
				}
			}
		}

		/** The contained object a nested map stands for: an existing one with its id, or a new one */
		EObject child(EReference reference, Object value, List<EObject> existing, String field) {
			if (!(value instanceof Map<?, ?> map)) {
				errors.add(new FieldError(field, "expects an object (" + reference.getEReferenceType().getName() + ")"));
				return null;
			}
			@SuppressWarnings("unchecked")
			Map<String, ?> fields = (Map<String, ?>) map;
			EClass type = type(reference.getEReferenceType(), fields.get(TYPE), field);
			if (type == null) {
				return null;
			}
			Object id = fields.get(ID);
			EObject child = null;
			if (id != null) {
				child = existing.stream().filter(e -> type.isInstance(e) && String.valueOf(id).equals(EcoreUtil.getID(e)))
						.findFirst().orElse(null);
			} else if (!reference.isMany() && existing.size() == 1 && existing.get(0).eClass() == type) {
				child = existing.get(0);
			}
			if (child == null) {
				if (type.isAbstract() || type.isInterface()) {
					errors.add(new FieldError(field + "." + TYPE, type.getName() + " is abstract, use one of "
							+ types.concreteSubtypes(type).stream().map(EClass::getName).toList()));
					return null;
				}
				child = EcoreUtil.create(type);
			}
			apply(child, fields, field, Set.of());
			return child;
		}

		void setReference(EObject target, EReference reference, Object value, String field) {
			List<Object> values = reference.isMany() ? asList(value) : List.of(value);
			List<Target> targets = new ArrayList<>();
			int i = 0;
			for (Object item : values) {
				String itemField = reference.isMany() ? field + "[" + i++ + "]" : field;
				Target spec = target(reference, item, itemField);
				if (spec != null) {
					targets.add(spec);
				}
			}
			if (targets.size() == values.size()) {
				pending.add(new PendingReference(target, reference, targets));
			}
		}

		Target target(EReference reference, Object value, String field) {
			if (value instanceof String id) {
				return new Target(reference.getEReferenceType(), id, null, field);
			}
			if (!(value instanceof Map<?, ?> map)) {
				errors.add(new FieldError(field, "expects a reference {\"type\": \"" + reference.getEReferenceType().getName()
						+ "\", \"id\": \"...\"}"));
				return null;
			}
			Object path = map.get(PATH);
			Object id = map.get(ID);
			if (path == null && id == null) {
				errors.add(new FieldError(field, "a reference needs an id (or the path of a contained object)"));
				return null;
			}
			EClass type = type(reference.getEReferenceType(), map.get(TYPE), field);
			return type == null ? null : new Target(type, id == null ? null : String.valueOf(id),
					path == null ? null : String.valueOf(path), field);
		}

		/** The type a map names, a subtype of the expected one, or the expected one */
		EClass type(EClass expected, Object name, String field) {
			if (name == null) {
				return expected;
			}
			EClass type = types.eClass(String.valueOf(name));
			if (type == null || !(type == expected || expected.isSuperTypeOf(type))) {
				errors.add(new FieldError(field + "." + TYPE, "expects " + expected.getName() + " or a subtype: "
						+ types.concreteSubtypes(expected).stream().map(EClass::getName).toList()));
				return null;
			}
			return type;
		}

		void finish(EObject root) throws InvalidRequestException {
			for (PendingReference reference : pending) {
				List<EObject> resolved = new ArrayList<>();
				for (Target target : reference.targets()) {
					EObject object = resolve(root, target);
					if (object != null) {
						resolved.add(object);
					}
				}
				if (resolved.size() == reference.targets().size()) {
					reference.owner().eSet(reference.reference(), reference.reference().isMany() ? resolved : resolved.get(0));
				}
			}
			if (!errors.isEmpty()) {
				throw new InvalidRequestException(errors);
			}
		}

		EObject resolve(EObject root, Target target) {
			if (target.path() != null) {
				EObject object = navigate(root, target.path());
				if (object == null || !target.type().isInstance(object)) {
					errors.add(new FieldError(target.field(), "no " + target.type().getName() + " at path " + target.path()));
					return null;
				}
				return object;
			}
			// first among the objects of this request, then in the repository
			for (TreeIterator<EObject> it = EcoreUtil.getAllContents(List.of(root)); it.hasNext();) {
				EObject candidate = it.next();
				if (target.type().isInstance(candidate) && target.id().equals(EcoreUtil.getID(candidate))) {
					return candidate;
				}
			}
			try {
				EObject object = lookup.find(target.type(), target.id());
				if (object == null || !target.type().isInstance(object)) {
					errors.add(new FieldError(target.field(), "no " + target.type().getName() + " with id " + target.id()));
					return null;
				}
				return object;
			} catch (IOException e) {
				errors.add(new FieldError(target.field(), "cannot read " + target.type().getName() + " " + target.id()
						+ ": " + e.getMessage()));
				return null;
			}
		}
	}

	/** The object at a path like {@code lineItems/0} below the root, or null */
	static EObject navigate(EObject root, String path) {
		EObject current = root;
		String[] segments = path.split("/");
		for (int i = 0; i < segments.length && current != null; i++) {
			EStructuralFeature feature = current.eClass().getEStructuralFeature(segments[i]);
			if (!(feature instanceof EReference reference) || !reference.isContainment() || !visible(reference)) {
				return null;
			}
			Object value = current.eGet(reference);
			if (reference.isMany()) {
				if (++i >= segments.length) {
					return null;
				}
				List<?> list = (List<?>) value;
				try {
					int index = Integer.parseInt(segments[i]);
					current = index >= 0 && index < list.size() ? (EObject) list.get(index) : null;
				} catch (NumberFormatException e) {
					return null;
				}
			} else {
				current = (EObject) value;
			}
		}
		return current;
	}

	private static List<Object> asList(Object value) {
		return value instanceof Collection<?> collection ? new ArrayList<>(collection) : List.of(value);
	}

	private static final Object INVALID = new Object();

	private record Target(EClass type, String id, String path, String field) {
	}

	private record PendingReference(EObject owner, EReference reference, List<Target> targets) {
	}
}
