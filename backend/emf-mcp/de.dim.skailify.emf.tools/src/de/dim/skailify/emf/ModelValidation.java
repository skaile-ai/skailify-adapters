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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.Diagnostician;
import org.eclipse.emf.ecore.util.EObjectValidator;
import org.eclipse.emf.ecore.util.EcoreUtil;

/**
 * The model's constraints before a save, as the EMF {@link Diagnostician} evaluates them
 * (including delegated constraints such as OCL). By default only the model's own constraints
 * count: EMF's structural checks (multiplicities, proxies, ...) are left out, unless asked for.
 * A violated constraint is told by the message its class keeps under a messages annotation for
 * the constraint's name, if configured, else by EMF's message.
 * <p>
 */
public final class ModelValidation {

	/** EMF's message for a delegated constraint: "The 'name' constraint is violated on ..." */
	private static final Pattern CONSTRAINT = Pattern.compile("'([^']+)'");

	/**
	 * One violated constraint.
	 *
	 * @param constraint the constraint's name, or null for a structural check
	 * @param message what is wrong
	 * @param type the class of the object it is violated on
	 * @param object a reference to that object ({@code id} or {@code path}), or null
	 * @param feature the feature it concerns, if the diagnostic names one
	 */
	public record Violation(String constraint, String message, String type, Map<String, Object> object, String feature) {

		public Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			if (constraint != null) {
				map.put("constraint", constraint);
			}
			map.put("message", message);
			map.put("type", type);
			if (object != null) {
				map.put("object", object);
			}
			if (feature != null) {
				map.put("feature", feature);
			}
			return map;
		}
	}

	private final String messagesSource;
	private final boolean structural;

	/**
	 * @param messagesSource annotation source for constraint messages, or null
	 * @param structural whether EMF's structural checks count as well
	 */
	public ModelValidation(String messagesSource, boolean structural) {
		this.messagesSource = messagesSource;
		this.structural = structural;
	}

	/** The violations on the objects and what they contain; empty when they are valid */
	public List<Violation> validate(List<? extends EObject> objects) {
		Set<Violation> violations = new LinkedHashSet<>();
		for (EObject object : objects) {
			Diagnostic diagnostic = Diagnostician.INSTANCE.validate(object);
			if (diagnostic.getSeverity() >= Diagnostic.ERROR) {
				collect(diagnostic, object, violations);
			}
		}
		return new ArrayList<>(violations);
	}

	private void collect(Diagnostic diagnostic, EObject root, Set<Violation> violations) {
		if (!diagnostic.getChildren().isEmpty()) {
			for (Diagnostic child : diagnostic.getChildren()) {
				collect(child, root, violations);
			}
			return;
		}
		if (diagnostic.getSeverity() < Diagnostic.ERROR) {
			return;
		}
		if (!structural && EObjectValidator.DIAGNOSTIC_SOURCE.equals(diagnostic.getSource())) {
			return;
		}
		EObject target = null;
		EStructuralFeature feature = null;
		for (Object data : diagnostic.getData()) {
			if (target == null && data instanceof EObject object && !(data instanceof EStructuralFeature)) {
				target = object;
			} else if (feature == null && data instanceof EStructuralFeature structuralFeature) {
				feature = structuralFeature;
			}
		}
		String message = diagnostic.getMessage();
		String constraint = null;
		if (!EObjectValidator.DIAGNOSTIC_SOURCE.equals(diagnostic.getSource())) {
			Matcher matcher = CONSTRAINT.matcher(message == null ? "" : message);
			if (matcher.find()) {
				constraint = matcher.group(1);
				String text = target == null ? null : annotated(target.eClass(), constraint);
				if (text != null) {
					message = text;
				}
			}
		}
		Map<String, Object> object = null;
		if (target != null) {
			object = new LinkedHashMap<>();
			String id = EcoreUtil.getID(target);
			if (id != null) {
				object.put(ObjectJson.ID, id);
			} else if (target != root && EcoreUtil.isAncestor(root, target)) {
				object.put(ObjectJson.PATH, ObjectJson.path(root, target));
			} else {
				object = null;
			}
		}
		violations.add(new Violation(constraint, message, target == null ? null : target.eClass().getName(), object,
				feature == null ? null : feature.getName()));
	}

	/** The message for the constraint on the class or one of its supertypes */
	private String annotated(EClass eClass, String constraint) {
		if (messagesSource == null) {
			return null;
		}
		String text = EcoreUtil.getAnnotation(eClass, messagesSource, constraint);
		if (text != null) {
			return text;
		}
		for (EClass supertype : eClass.getESuperTypes()) {
			text = annotated(supertype, constraint);
			if (text != null) {
				return text;
			}
		}
		return null;
	}
}
