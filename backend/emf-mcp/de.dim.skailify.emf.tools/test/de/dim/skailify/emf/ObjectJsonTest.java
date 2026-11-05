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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.common.util.DiagnosticChain;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EValidator;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.common.util.BasicDiagnostic;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The generic EMF support against a small dynamic model: invoices with lines and a supplier. */
class ObjectJsonTest {

	static EPackage pkg;
	static EClass named, party, invoice, line, booking;
	static EEnum status;

	@BeforeAll
	static void model() {
		EcoreFactory f = EcoreFactory.eINSTANCE;
		pkg = f.createEPackage();
		pkg.setName("shop");
		pkg.setNsURI("urn:test:shop");
		named = eClass("Named", true);
		EAttribute id = attribute(named, "id", EcorePackage.Literals.ESTRING);
		id.setID(true);
		attribute(named, "name", EcorePackage.Literals.ESTRING);
		party = eClass("Party", false);
		party.getESuperTypes().add(named);
		status = f.createEEnum();
		status.setName("Status");
		for (String literal : List.of("OPEN", "PAID")) {
			var l = f.createEEnumLiteral();
			l.setName(literal);
			l.setValue(status.getELiterals().size());
			status.getELiterals().add(l);
		}
		pkg.getEClassifiers().add(status);
		line = eClass("Line", false);
		attribute(line, "id", EcorePackage.Literals.ESTRING).setID(true);
		attribute(line, "net", EcorePackage.Literals.EBIG_DECIMAL);
		booking = eClass("Booking", false);
		reference(booking, "line", line, false, false);
		invoice = eClass("Invoice", false);
		invoice.getESuperTypes().add(named);
		attribute(invoice, "status", status);
		attribute(invoice, "gross", EcorePackage.Literals.EBIG_DECIMAL);
		attribute(invoice, "tags", EcorePackage.Literals.ESTRING).setUpperBound(-1);
		reference(invoice, "supplier", party, false, false);
		reference(invoice, "lines", line, true, true);
		reference(invoice, "bookings", booking, true, true);
		EcoreUtil.setAnnotation(invoice, ModelDescription.ECORE, "constraints", "grossPositive");
		EcoreUtil.setAnnotation(invoice, "urn:messages", "grossPositive", "Gross must be positive.");
		EValidator.Registry.INSTANCE.put(pkg, new EValidator() {
			@Override
			public boolean validate(EClass eClass, EObject object, DiagnosticChain diagnostics, Map<Object, Object> context) {
				if (eClass == invoice && object.eGet(invoice.getEStructuralFeature("gross")) instanceof BigDecimal gross
						&& gross.signum() <= 0 && diagnostics != null) {
					diagnostics.add(new BasicDiagnostic(Diagnostic.ERROR, "shop", 1,
							"The 'grossPositive' constraint is violated on 'Invoice'", new Object[] { object }));
					return false;
				}
				return true;
			}

			@Override
			public boolean validate(EObject object, DiagnosticChain diagnostics, Map<Object, Object> context) {
				return validate(object.eClass(), object, diagnostics, context);
			}

			@Override
			public boolean validate(EDataType dataType, Object value, DiagnosticChain diagnostics, Map<Object, Object> context) {
				return true;
			}
		});
	}

	static EClass eClass(String name, boolean isAbstract) {
		EClass c = EcoreFactory.eINSTANCE.createEClass();
		c.setName(name);
		c.setAbstract(isAbstract);
		pkg.getEClassifiers().add(c);
		return c;
	}

	static EAttribute attribute(EClass owner, String name, org.eclipse.emf.ecore.EClassifier type) {
		EAttribute a = EcoreFactory.eINSTANCE.createEAttribute();
		a.setName(name);
		a.setEType(type);
		owner.getEStructuralFeatures().add(a);
		return a;
	}

	static EReference reference(EClass owner, String name, EClass type, boolean many, boolean containment) {
		EReference r = EcoreFactory.eINSTANCE.createEReference();
		r.setName(name);
		r.setEType(type);
		r.setUpperBound(many ? -1 : 1);
		r.setContainment(containment);
		owner.getEStructuralFeatures().add(r);
		return r;
	}

	final ModelTypes types = new ModelTypes(pkg, List.of());
	final ObjectJson json = new ObjectJson(types, List.of("name"));
	final Map<String, EObject> stored = new HashMap<>();

	EObject party(String id, String name) {
		EObject p = EcoreUtil.create(party);
		p.eSet(named.getEStructuralFeature("id"), id);
		p.eSet(named.getEStructuralFeature("name"), name);
		stored.put(id, p);
		return p;
	}

	EObject find(EClass type, String id) {
		EObject object = stored.get(id);
		return object != null && type.isInstance(object) ? object : null;
	}

	@Test
	void createsAnObjectWithNestedObjectsReferencesAndPaths() throws Exception {
		party("p1", "Müller");
		EObject created = json.create(invoice, Map.of(
				"name", "RE-1",
				"status", "OPEN",
				"gross", "119.00",
				"tags", List.of("a", "b"),
				"supplier", Map.of("type", "Party", "id", "p1"),
				"lines", List.of(Map.of("net", 100), Map.of("id", "l2", "net", 19.5)),
				"bookings", List.of(Map.of("line", Map.of("path", "lines/0")), Map.of("line", Map.of("id", "l2")))),
				this::find);

		assertThat(created.eGet(invoice.getEStructuralFeature("gross"))).isEqualTo(new BigDecimal("119.00"));
		assertThat(((EObject) created.eGet(invoice.getEStructuralFeature("supplier"))).eGet(named.getEStructuralFeature("name")))
				.isEqualTo("Müller");
		@SuppressWarnings("unchecked")
		List<EObject> lines = (List<EObject>) created.eGet(invoice.getEStructuralFeature("lines"));
		@SuppressWarnings("unchecked")
		List<EObject> bookings = (List<EObject>) created.eGet(invoice.getEStructuralFeature("bookings"));
		assertThat(bookings.get(0).eGet(booking.getEStructuralFeature("line"))).isSameAs(lines.get(0));
		assertThat(bookings.get(1).eGet(booking.getEStructuralFeature("line"))).isSameAs(lines.get(1));

		Map<String, Object> written = json.write(created);
		assertThat(written).containsEntry("type", "Invoice").containsEntry("status", "OPEN")
				.containsEntry("gross", new BigDecimal("119.00")).containsEntry("tags", List.of("a", "b"))
				.containsEntry("supplier", Map.of("type", "Party", "id", "p1", "label", "Müller"));
		assertThat(written.get("bookings")).isEqualTo(List.of(
				Map.of("type", "Booking", "line", Map.of("type", "Line", "path", "lines/0")),
				Map.of("type", "Booking", "line", Map.of("type", "Line", "id", "l2"))));
	}

	@Test
	void collectsEveryMistakeOfARequest() {
		assertThatThrownBy(() -> json.create(invoice, Map.of("id", "x", "status", "LOST", "gross", "lots", "colour", "red",
				"supplier", Map.of("type", "Party", "id", "nobody"), "lines", List.of(Map.of("type", "Party"))), this::find))
				.isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getErrors())
						.extracting(FieldError::field)
						.containsExactlyInAnyOrder("id", "status", "gross", "colour", "supplier", "lines[0].type"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void updatesOnlyTheGivenFieldsAndKeepsContainedObjectsById() throws Exception {
		EObject invoiceObject = json.create(invoice, Map.of("name", "RE-2", "gross", 10,
				"lines", List.of(Map.of("id", "a", "net", 1), Map.of("id", "b", "net", 2))), this::find);
		@SuppressWarnings("unchecked")
		EObject lineB = ((List<EObject>) invoiceObject.eGet(invoice.getEStructuralFeature("lines"))).get(1);

		Map<String, Object> fields = new HashMap<>();
		fields.put("gross", null);
		fields.put("lines", List.of(Map.of("id", "b", "net", "2.50")));
		json.update(invoiceObject, fields, this::find);

		assertThat(invoiceObject.eIsSet(invoice.getEStructuralFeature("gross"))).isFalse();
		assertThat(invoiceObject.eGet(named.getEStructuralFeature("name"))).isEqualTo("RE-2");
		assertThat((List<Object>) invoiceObject.eGet(invoice.getEStructuralFeature("lines"))).containsExactly(lineB);
		assertThat(lineB.eGet(line.getEStructuralFeature("net"))).isEqualTo(new BigDecimal("2.50"));
	}

	@Test
	void reportsConstraintViolationsWithTheModelsMessage() throws Exception {
		EObject bad = json.create(invoice, Map.of("name", "RE-3", "gross", "-1"), this::find);
		assertThat(new ModelValidation("urn:messages", false).validate(List.of(bad)))
				.singleElement().satisfies(v -> {
					assertThat(v.constraint()).isEqualTo("grossPositive");
					assertThat(v.message()).isEqualTo("Gross must be positive.");
					assertThat(v.type()).isEqualTo("Invoice");
				});
		EObject good = json.create(invoice, Map.of("name", "RE-3", "gross", "1"), this::find);
		assertThat(new ModelValidation("urn:messages", false).validate(List.of(good))).isEmpty();
	}

	@Test
	void refusesATakenValuePerClassIgnoringCase() {
		EObject existing = party("p1", "Müller");
		EObject same = party("p2", " müller ");
		stored.remove("p2");
		UniqueValues unique = new UniqueValues(List.of("Named.name"));
		assertThat(unique.taken(List.of(same), List.of(existing))).hasValueSatisfying(c -> {
			assertThat(c.value()).isEqualTo("müller");
			assertThat(c.ownerId()).isEqualTo("p1");
		});
		assertThat(unique.taken(List.of(existing), List.of(existing))).as("keeps its own name").isEmpty();
	}

	@Test
	void describesClassesConstraintsAndSchemas() {
		ModelDescription description = new ModelDescription(types, "urn:messages");
		Map<String, Object> model = description.describe();
		assertThat(model).containsEntry("name", "shop");
		Map<String, Object> invoiceDescription = description.describe(invoice);
		assertThat(invoiceDescription).containsEntry("stored", true);
		assertThat(invoiceDescription.get("constraints"))
				.isEqualTo(List.of(Map.of("name", "grossPositive", "message", "Gross must be positive.")));
		@SuppressWarnings("unchecked")
		Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>) invoiceDescription.get("schema")).get("properties");
		assertThat(properties).containsKeys("status", "gross", "supplier", "lines");
		assertThat(types.rootClasses()).containsExactlyInAnyOrder(party, invoice);
	}

	@Test
	void turnsFiltersIntoQueryExpressions() throws Exception {
		assertThat(ObjectFilter.expression(invoice, Map.of("status", List.of("OPEN", "PAID"),
				"supplier", "p1", "supplier.name", Map.of("contains", "mü"), "gross", Map.of("gt", 0)))).isNotNull();
		assertThatThrownBy(() -> ObjectFilter.expression(invoice, Map.of("lines", "x", "status", "LOST", "nope", 1)))
				.isInstanceOfSatisfying(InvalidRequestException.class,
						e -> assertThat(e.getErrors()).extracting(FieldError::field)
								.containsExactlyInAnyOrder("filter.lines", "filter.status", "filter.nope"));
	}
}
