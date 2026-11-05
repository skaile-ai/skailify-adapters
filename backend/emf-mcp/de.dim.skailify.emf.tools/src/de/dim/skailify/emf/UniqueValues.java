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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

/**
 * Values given once per class: two objects of the same class with the same value of a unique
 * attribute (ignoring case and surrounding blanks) are refused. Objects of different classes may
 * share a value. A unique attribute is named {@code Class.attribute} for the class that declares
 * it, and holds for that class and all its subclasses (e.g. {@code Named.name}).
 * <p>
 */
public final class UniqueValues {

	private final List<String> qualifiedNames;

	/** @param qualifiedNames attributes as {@code DeclaringClass.attribute} */
	public UniqueValues(List<String> qualifiedNames) {
		this.qualifiedNames = List.copyOf(qualifiedNames);
	}

	/** The unique attributes of the class */
	public List<EAttribute> attributes(EClass eClass) {
		List<EAttribute> result = new ArrayList<>();
		for (EAttribute attribute : eClass.getEAllAttributes()) {
			if (!attribute.isMany()
					&& qualifiedNames.contains(attribute.getEContainingClass().getName() + "." + attribute.getName())) {
				result.add(attribute);
			}
		}
		return result;
	}

	/**
	 * The first value among {@code saving} that another object of its class already has, in
	 * {@code existing} (the stored objects of the class) or in {@code saving} itself; empty when
	 * all are free. An object keeps its own value: the same id is no conflict.
	 */
	public Optional<Conflict> taken(List<? extends EObject> saving, List<? extends EObject> existing) {
		for (EObject object : saving) {
			for (EAttribute attribute : attributes(object.eClass())) {
				Map<String, EObject> owners = new HashMap<>();
				for (EObject other : existing) {
					if (other.eClass() == object.eClass() && saving.stream().noneMatch(s -> sameId(s, other))) {
						key(other, attribute).ifPresent(key -> owners.put(key, other));
					}
				}
				for (EObject other : saving) {
					if (other.eClass() == object.eClass() && other != object) {
						key(other, attribute).ifPresent(key -> owners.putIfAbsent(key, other));
					}
				}
				Optional<String> key = key(object, attribute);
				if (key.isPresent() && owners.containsKey(key.get()) && !sameId(owners.get(key.get()), object)) {
					return Optional.of(new Conflict(object.eClass().getName(), attribute,
							String.valueOf(object.eGet(attribute)).strip(), EcoreUtil.getID(owners.get(key.get()))));
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * A value already taken.
	 *
	 * @param ownerId the id of the object that has it, or null when it is another one of this save
	 */
	public record Conflict(String type, EStructuralFeature feature, String value, String ownerId) {
	}

	private static boolean sameId(EObject a, EObject b) {
		String id = EcoreUtil.getID(a);
		return id != null && Objects.equals(id, EcoreUtil.getID(b));
	}

	private static Optional<String> key(EObject object, EAttribute attribute) {
		Object value = object.eGet(attribute);
		String text = value == null ? null : String.valueOf(value).strip();
		return text == null || text.isEmpty() ? Optional.empty() : Optional.of(text.toLowerCase(Locale.ROOT));
	}
}
