/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf;

import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

/**
 * Reads what a model says to Skailify, in EAnnotations with source {@value #SOURCE}:
 *
 * <pre>
 * &lt;eAnnotations source="https://data-in-motion.biz/skailify"&gt;
 *   &lt;details key="expose" value="false"/&gt;
 * &lt;/eAnnotations&gt;
 * </pre>
 *
 * <table>
 * <tr><th>key</th><th>on</th><th>meaning</th></tr>
 * <tr><td>{@value #EXPOSE}</td><td>class, feature</td><td>{@code false} hides it from agents: the
 * tools neither show nor accept it, a reference to a hidden class is hidden too</td></tr>
 * <tr><td>{@value #DESCRIPTION}</td><td>package, class, feature</td><td>what an agent should know,
 * in place of the GenModel documentation</td></tr>
 * </table>
 *
 * Other keys in the same annotation are for other readers (the Skailify manifest reads
 * {@code grant}, {@code effectClass} and {@code approval}) and are ignored here.
 */
public final class SkailifyAnnotations {

	/** The annotation source */
	public static final String SOURCE = "https://data-in-motion.biz/skailify";

	public static final String EXPOSE = "expose";
	public static final String DESCRIPTION = "description";

	private SkailifyAnnotations() {
	}

	/** The value of the key, or null */
	public static String get(EModelElement element, String key) {
		String value = element == null ? null : EcoreUtil.getAnnotation(element, SOURCE, key);
		return value == null || value.isBlank() ? null : value.strip();
	}

	/** Whether the class is hidden from agents */
	public static boolean hidden(EClassifier classifier) {
		return "false".equalsIgnoreCase(get(classifier, EXPOSE));
	}

	/** Whether the feature is hidden: by its own annotation, or because it refers to a hidden class */
	public static boolean hidden(EStructuralFeature feature) {
		if ("false".equalsIgnoreCase(get(feature, EXPOSE))) {
			return true;
		}
		return feature instanceof EReference reference && hidden(reference.getEReferenceType());
	}

	/** What an agent should know about the element: the Skailify description, else the documentation */
	public static String description(EModelElement element) {
		String description = get(element, DESCRIPTION);
		return description != null ? description : EcoreUtil.getDocumentation(element);
	}
}
