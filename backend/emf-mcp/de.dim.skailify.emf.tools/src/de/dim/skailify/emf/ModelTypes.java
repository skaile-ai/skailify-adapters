/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;

/**
 * The classes of a model an agent may see, by name: those of the EPackage and its subpackages,
 * less the excluded ones and those the model hides ({@link SkailifyAnnotations#hidden(EClassifier)}).
 * Class names must be unique across the subpackages; the first wins.
 */
public final class ModelTypes {

	private final EPackage ePackage;
	private final Map<String, EClassifier> classifiers = new LinkedHashMap<>();

	/**
	 * @param excluded names of classes to hide; their subclasses stay visible unless named too
	 */
	public ModelTypes(EPackage ePackage, Collection<String> excluded) {
		this.ePackage = ePackage;
		collect(ePackage, excluded);
	}

	private void collect(EPackage current, Collection<String> excluded) {
		for (EClassifier classifier : current.getEClassifiers()) {
			if (!excluded.contains(classifier.getName()) && !SkailifyAnnotations.hidden(classifier)) {
				classifiers.putIfAbsent(classifier.getName(), classifier);
			}
		}
		for (EPackage sub : current.getESubpackages()) {
			collect(sub, excluded);
		}
	}

	public EPackage ePackage() {
		return ePackage;
	}

	/** All visible classifiers (classes, enums, data types) in model order */
	public Collection<EClassifier> classifiers() {
		return classifiers.values();
	}

	/** All visible classes in model order */
	public List<EClass> eClasses() {
		return classifiers.values().stream().filter(EClass.class::isInstance).map(EClass.class::cast).toList();
	}

	/** The visible class of that name, or null */
	public EClass eClass(String name) {
		return name != null && classifiers.get(name) instanceof EClass eClass ? eClass : null;
	}

	/** The visible concrete classes that are the class or extend it */
	public List<EClass> concreteSubtypes(EClass eClass) {
		List<EClass> result = new ArrayList<>();
		for (EClass candidate : eClasses()) {
			if (!candidate.isAbstract() && !candidate.isInterface()
					&& (candidate == eClass || eClass.isSuperTypeOf(candidate))) {
				result.add(candidate);
			}
		}
		return result;
	}

	/** Visible classes whose instances are stored on their own: concrete classes that are no containment target */
	public List<EClass> rootClasses() {
		List<EClass> contained = new ArrayList<>();
		for (EClass eClass : eClasses()) {
			eClass.getEAllContainments().forEach(r -> contained.add(r.getEReferenceType()));
		}
		return eClasses().stream().filter(c -> !c.isAbstract() && !c.isInterface())
				.filter(c -> contained.stream().noneMatch(t -> t == c || t.isSuperTypeOf(c))).toList();
	}
}
