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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The {@code skailify} EAnnotation against a small dynamic model: a hidden class, a hidden
 * attribute, a reference to the hidden class, descriptions.
 */
class SkailifyAnnotationsTest {

	static EPackage pkg;
	static EClass base, account, profile, payment;
	static EAttribute secret;
	static EReference profileRef;

	@BeforeAll
	static void model() {
		EcoreFactory f = EcoreFactory.eINSTANCE;
		pkg = f.createEPackage();
		pkg.setName("bank");
		pkg.setNsURI("urn:test:bank");
		annotate(pkg, "effectClass", "routine", "approval", "required", "description", "A bank");
		base = eClass("Base", true);
		attribute(base, "id").setID(true);
		annotate(base, "grant", "bank.base", "effectClass", "privileged");
		profile = eClass("Profile", false);
		attribute(profile, "id").setID(true);
		annotate(profile, "expose", "false");
		account = eClass("Account", false);
		account.getESuperTypes().add(base);
		attribute(account, "iban");
		secret = attribute(account, "pin");
		annotate(secret, "expose", "FALSE");
		profileRef = f.createEReference();
		profileRef.setName("profile");
		profileRef.setEType(profile);
		account.getEStructuralFeatures().add(profileRef);
		payment = eClass("Payment", false);
		attribute(payment, "id").setID(true);
		attribute(payment, "amount");
		annotate(payment, "approval", "optional", "effectClass", "dangerous", "description", "Money that moved");
	}

	@Test
	void hidesAnnotatedClassesAndFeaturesAndReferencesToHiddenClasses() {
		assertThat(SkailifyAnnotations.hidden(profile)).isTrue();
		assertThat(SkailifyAnnotations.hidden(account)).isFalse();
		assertThat(SkailifyAnnotations.hidden(secret)).isTrue();
		assertThat(SkailifyAnnotations.hidden(profileRef)).isTrue();
		assertThat(SkailifyAnnotations.hidden(account.getEStructuralFeature("iban"))).isFalse();
	}

	@Test
	void prefersTheSkailifyDescriptionOverTheDocumentation() {
		EcoreUtil.setDocumentation(payment, "Generated documentation");
		assertThat(SkailifyAnnotations.description(payment)).isEqualTo("Money that moved");
		EcoreUtil.setDocumentation(account, "An account");
		assertThat(SkailifyAnnotations.description(account)).isEqualTo("An account");
	}

	@Test
	void theToolsNeitherShowNorAcceptHiddenParts() throws Exception {
		ModelTypes types = new ModelTypes(pkg, List.of());
		assertThat(types.eClasses()).extracting(EClass::getName).containsExactly("Base", "Account", "Payment");
		assertThat(types.eClass("Profile")).isNull();

		EObject a = EcoreUtil.create(account);
		a.eSet(account.getEStructuralFeature("id"), "a1");
		a.eSet(account.getEStructuralFeature("iban"), "DE00");
		a.eSet(secret, "1234");
		ObjectJson json = new ObjectJson(types, List.of("name"));
		assertThat(json.write(a)).containsEntry("iban", "DE00").doesNotContainKeys("pin", "profile");

		assertThatThrownBy(() -> json.update(a, Map.of("pin", "0000"), (type, id) -> null))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(a.eGet(secret)).isEqualTo("1234");

		Map<String, Object> described = new ModelDescription(types, null).describe(account);
		assertThat(described.toString()).doesNotContain("pin").doesNotContain("Profile");
		assertThatThrownBy(() -> ObjectFilter.expression(account, Map.of("pin", "1234")))
				.isInstanceOf(InvalidRequestException.class);
	}

	private static void annotate(org.eclipse.emf.ecore.EModelElement element, String... keysAndValues) {
		for (int i = 0; i < keysAndValues.length; i += 2) {
			EcoreUtil.setAnnotation(element, SkailifyAnnotations.SOURCE, keysAndValues[i], keysAndValues[i + 1]);
		}
	}

	private static EClass eClass(String name, boolean isAbstract) {
		EClass eClass = EcoreFactory.eINSTANCE.createEClass();
		eClass.setName(name);
		eClass.setAbstract(isAbstract);
		pkg.getEClassifiers().add(eClass);
		return eClass;
	}

	private static EAttribute attribute(EClass owner, String name) {
		EAttribute attribute = EcoreFactory.eINSTANCE.createEAttribute();
		attribute.setName(name);
		attribute.setEType(EcorePackage.Literals.ESTRING);
		owner.getEStructuralFeatures().add(attribute);
		return attribute;
	}
}
