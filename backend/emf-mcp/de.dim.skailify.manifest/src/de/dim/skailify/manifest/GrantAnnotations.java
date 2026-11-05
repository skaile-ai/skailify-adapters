/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;

import de.dim.skailify.emf.SkailifyAnnotations;
import de.dim.skailify.manifest.ToolGrant.TypeGrant;

/**
 * Reads the grant keys of the {@code skailify} EAnnotation ({@link SkailifyAnnotations#SOURCE}):
 *
 * <table>
 * <tr><th>key</th><th>on</th><th>meaning</th></tr>
 * <tr><td>{@value #GRANT}</td><td>class</td><td>the grant that covers changes to its objects</td></tr>
 * <tr><td>{@value #EFFECT_CLASS}</td><td>package, class</td><td>{@code routine}, {@code external}
 * or {@code privileged}: how much a change weighs (on the package: the default)</td></tr>
 * <tr><td>{@value #APPROVAL}</td><td>package, class</td><td>{@code none}, {@code optional} or
 * {@code required}: whether a person approves a change by default</td></tr>
 * </table>
 *
 * A class inherits from its supertypes, then from the package. A value outside the vocabulary
 * counts as not given. Classes the model hides ({@code expose=false}) have no grants.
 */
final class GrantAnnotations {

	static final String GRANT = "grant";
	static final String EFFECT_CLASS = "effectClass";
	static final String APPROVAL = "approval";

	private GrantAnnotations() {
	}

	/** The grant covering changes to objects of the class or its supertypes, or null */
	static String grant(EClass eClass) {
		return inherited(eClass, GRANT, null);
	}

	/** The effect class of a change: the class's, a supertype's, the package's, else null */
	static String effectClass(EClass eClass) {
		return inherited(eClass, EFFECT_CLASS, ToolGrant.EFFECT_CLASSES);
	}

	/** The approval default of a change: the class's, a supertype's, the package's, else null */
	static String approval(EClass eClass) {
		return inherited(eClass, APPROVAL, ToolGrant.APPROVALS);
	}

	/** The package-wide effect class, or routine */
	static String effectClass(EPackage ePackage) {
		return Objects.requireNonNullElse(valid(SkailifyAnnotations.get(ePackage, EFFECT_CLASS), ToolGrant.EFFECT_CLASSES),
				ToolGrant.ROUTINE);
	}

	/** The package-wide approval default, or required */
	static String approval(EPackage ePackage) {
		return Objects.requireNonNullElse(valid(SkailifyAnnotations.get(ePackage, APPROVAL), ToolGrant.APPROVALS),
				ToolGrant.REQUIRED);
	}

	/**
	 * The grant of a tool that changes objects of the package: the package's effect class and
	 * approval, refined for each visible concrete class that has a grant, or another effect class
	 * or approval
	 */
	static ToolGrant effect(String name, String scope, String description, EPackage ePackage) {
		String effectClass = effectClass(ePackage);
		String approval = approval(ePackage);
		List<TypeGrant> types = new ArrayList<>();
		for (EClass eClass : classes(ePackage, new ArrayList<>())) {
			if (eClass.isAbstract() || eClass.isInterface()) {
				continue;
			}
			String grant = grant(eClass);
			String typeEffect = Objects.requireNonNullElse(effectClass(eClass), effectClass);
			String typeApproval = Objects.requireNonNullElse(approval(eClass), approval);
			if (grant != null || !typeEffect.equals(effectClass) || !typeApproval.equals(approval)) {
				types.add(new TypeGrant(eClass.getName(), grant, typeEffect, typeApproval, null));
			}
		}
		return new ToolGrant(name, ToolGrant.EFFECT, effectClass, approval, scope, description, types);
	}

	private static List<EClass> classes(EPackage ePackage, List<EClass> result) {
		for (EClassifier classifier : ePackage.getEClassifiers()) {
			if (classifier instanceof EClass eClass && !SkailifyAnnotations.hidden(eClass)) {
				result.add(eClass);
			}
		}
		ePackage.getESubpackages().forEach(sub -> classes(sub, result));
		return result;
	}

	private static String inherited(EClass eClass, String key, Set<String> allowed) {
		String value = valid(SkailifyAnnotations.get(eClass, key), allowed);
		if (value != null) {
			return value;
		}
		for (EClass superType : eClass.getEAllSuperTypes()) {
			value = valid(SkailifyAnnotations.get(superType, key), allowed);
			if (value != null) {
				return value;
			}
		}
		EObject container = eClass.eContainer();
		return container instanceof EPackage ePackage && allowed != null
				? valid(SkailifyAnnotations.get(ePackage, key), allowed) : null;
	}

	private static String valid(String value, Set<String> allowed) {
		return value == null || allowed == null || allowed.contains(value) ? value : null;
	}
}
