/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.emf;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.util.EcoreUtil;

/**
 * What an agent needs to know about a model, from the Ecore alone: classes with their
 * attributes, references, documentation and constraints (with the expressions of delegated
 * constraints, e.g. OCL), enums with their literals, and a JSON schema per class for the fields
 * of a create or update.
 */
public final class ModelDescription {

	/** Source of the annotation carrying {@code constraints} and {@code validationDelegates} */
	public static final String ECORE = EcorePackage.eNS_URI;

	private static final Set<Class<?>> INTEGRAL = Set.of(int.class, long.class, short.class, byte.class,
			Integer.class, Long.class, Short.class, Byte.class, BigInteger.class);
	private static final Set<Class<?>> NUMBERS = Set.of(double.class, float.class, Double.class, Float.class,
			BigDecimal.class);

	private final ModelTypes types;
	private final String messagesSource;

	/**
	 * @param messagesSource the annotation source under which a class keeps a message per
	 *            constraint name, or null
	 */
	public ModelDescription(ModelTypes types, String messagesSource) {
		this.types = types;
		this.messagesSource = messagesSource;
	}

	/** The whole model: package, classes (with {@code stored}: may be queried and saved), enums */
	public Map<String, Object> describe() {
		EPackage ePackage = types.ePackage();
		Map<String, Object> model = new LinkedHashMap<>();
		model.put("name", ePackage.getName());
		model.put("nsURI", ePackage.getNsURI());
		putDocumentation(model, ePackage);
		List<EClass> stored = types.rootClasses();
		List<Object> classes = new ArrayList<>();
		List<Object> enums = new ArrayList<>();
		for (EClassifier classifier : types.classifiers()) {
			if (classifier instanceof EClass eClass) {
				classes.add(describe(eClass, stored));
			} else if (classifier instanceof EEnum eEnum) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("name", eEnum.getName());
				putDocumentation(json, eEnum);
				json.put("literals", literals(eEnum));
				enums.add(json);
			}
		}
		model.put("classes", classes);
		model.put("enums", enums);
		return model;
	}

	/** One class, with the JSON schema of its fields */
	public Map<String, Object> describe(EClass eClass) {
		Map<String, Object> json = describe(eClass, types.rootClasses());
		json.put("schema", schema(eClass));
		return json;
	}

	private Map<String, Object> describe(EClass eClass, List<EClass> stored) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("name", eClass.getName());
		if (eClass.isAbstract() || eClass.isInterface()) {
			json.put("abstract", true);
			json.put("subtypes", types.concreteSubtypes(eClass).stream().map(EClass::getName).toList());
		}
		json.put("stored", stored.contains(eClass)
				|| (eClass.isAbstract() && types.concreteSubtypes(eClass).stream().anyMatch(stored::contains)));
		List<String> superTypes = eClass.getESuperTypes().stream().filter(types.eClasses()::contains).map(EClass::getName).toList();
		if (!superTypes.isEmpty()) {
			json.put("superTypes", superTypes);
		}
		putDocumentation(json, eClass);
		List<Object> attributes = new ArrayList<>();
		List<Object> references = new ArrayList<>();
		for (EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
			if (!ObjectJson.visible(feature)) {
				continue;
			}
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("name", feature.getName());
			f.put("type", feature.getEType().getName());
			if (feature.isMany()) {
				f.put("many", true);
			}
			if (feature.getLowerBound() > 0) {
				f.put("required", true);
			}
			if (feature.getEContainingClass() != eClass) {
				f.put("from", feature.getEContainingClass().getName());
			}
			if (feature instanceof EAttribute attribute) {
				if (attribute.isID()) {
					f.put("id", true);
				}
				if (attribute.getDefaultValueLiteral() != null) {
					f.put("default", attribute.getDefaultValueLiteral());
				}
				putDocumentation(f, feature);
				attributes.add(f);
			} else {
				EReference reference = (EReference) feature;
				if (reference.isContainment()) {
					f.put("containment", true);
				}
				putDocumentation(f, feature);
				references.add(f);
			}
		}
		json.put("attributes", attributes);
		json.put("references", references);
		List<Object> constraints = constraints(eClass);
		if (!constraints.isEmpty()) {
			json.put("constraints", constraints);
		}
		return json;
	}

	/** The constraints of the class and its supertypes: name, expression (if delegated), message */
	List<Object> constraints(EClass eClass) {
		List<Object> result = new ArrayList<>();
		List<String> delegates = EcoreUtil.getValidationDelegates(eClass.getEPackage());
		List<EClass> classes = new ArrayList<>(eClass.getEAllSuperTypes());
		classes.add(eClass);
		for (EClass owner : classes) {
			for (String name : EcoreUtil.getConstraints(owner)) {
				Map<String, Object> c = new LinkedHashMap<>();
				c.put("name", name);
				if (owner != eClass) {
					c.put("from", owner.getName());
				}
				for (String delegate : delegates) {
					String expression = EcoreUtil.getAnnotation(owner, delegate, name);
					if (expression != null) {
						c.put("expression", expression);
						c.put("language", delegate);
						break;
					}
				}
				String message = messagesSource == null ? null : EcoreUtil.getAnnotation(owner, messagesSource, name);
				if (message != null) {
					c.put("message", message);
				}
				result.add(c);
			}
		}
		return result;
	}

	/**
	 * The JSON schema of the fields of a create or update for the class: attributes by type,
	 * contained objects nested (one level of their own fields, deeper ones as objects), other
	 * references as {@code {type, id}}.
	 */
	public Map<String, Object> schema(EClass eClass) {
		return schema(eClass, 0);
	}

	private Map<String, Object> schema(EClass eClass, int depth) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		String documentation = SkailifyAnnotations.description(eClass);
		if (documentation != null) {
			schema.put("description", documentation);
		}
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(ObjectJson.TYPE, Map.of("const", eClass.getName()));
		List<String> required = new ArrayList<>();
		for (EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
			if (!ObjectJson.visible(feature) || !feature.isChangeable()) {
				continue;
			}
			Map<String, Object> property;
			if (feature instanceof EAttribute attribute) {
				property = attributeSchema(attribute.getEAttributeType());
				if (attribute.isID()) {
					property.put("description", "Assigned by the repository on create; on contained objects it identifies them in an update");
				}
			} else {
				EReference reference = (EReference) feature;
				if (reference.isContainment()) {
					property = depth < 1 ? schema(reference.getEReferenceType(), depth + 1)
							: new LinkedHashMap<>(Map.of("type", "object"));
				} else {
					property = referenceSchema(reference.getEReferenceType());
				}
			}
			String documentation2 = SkailifyAnnotations.description(feature);
			if (documentation2 != null && !property.containsKey("description")) {
				property.put("description", documentation2);
			}
			if (feature.isMany()) {
				Map<String, Object> array = new LinkedHashMap<>();
				array.put("type", "array");
				array.put("items", property);
				property = array;
			}
			properties.put(feature.getName(), property);
			if (feature.getLowerBound() > 0 && !(feature instanceof EAttribute a && a.isID())) {
				required.add(feature.getName());
			}
		}
		schema.put("properties", properties);
		if (!required.isEmpty()) {
			schema.put("required", required);
		}
		return schema;
	}

	private Map<String, Object> referenceSchema(EClass target) {
		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> subtypes = types.concreteSubtypes(target).stream().map(EClass::getName).toList();
		properties.put(ObjectJson.TYPE, Map.of("enum", subtypes));
		properties.put(ObjectJson.ID, Map.of("type", "string"));
		properties.put(ObjectJson.PATH, Map.of("type", "string",
				"description", "Instead of id: path of an object contained in the same root object, e.g. lineItems/0"));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("description", "Reference to a " + target.getName());
		schema.put("properties", properties);
		return schema;
	}

	private static Map<String, Object> attributeSchema(EDataType type) {
		Map<String, Object> schema = new LinkedHashMap<>();
		if (type instanceof EEnum eEnum) {
			schema.put("type", "string");
			schema.put("enum", eEnum.getELiterals().stream().map(EEnumLiteral::getLiteral).toList());
			return schema;
		}
		Class<?> instanceClass = type.getInstanceClass();
		if (instanceClass == boolean.class || instanceClass == Boolean.class) {
			schema.put("type", "boolean");
		} else if (INTEGRAL.contains(instanceClass)) {
			schema.put("type", "integer");
		} else if (NUMBERS.contains(instanceClass)) {
			schema.put("type", List.of("number", "string"));
			if (instanceClass == BigDecimal.class) {
				schema.put("description", "Decimal; as a string to keep every digit, e.g. \"119.00\"");
			}
		} else if (instanceClass != null && "java.time.LocalDate".equals(instanceClass.getName())) {
			schema.put("type", "string");
			schema.put("format", "date");
		} else if (instanceClass == java.util.Date.class) {
			schema.put("type", "string");
			schema.put("format", "date-time");
		} else {
			schema.put("type", "string");
		}
		return schema;
	}

	private static List<Object> literals(EEnum eEnum) {
		List<Object> literals = new ArrayList<>();
		for (EEnumLiteral literal : eEnum.getELiterals()) {
			String documentation = EcoreUtil.getDocumentation(literal);
			if (documentation == null) {
				literals.add(literal.getLiteral());
			} else {
				literals.add(Map.of("literal", literal.getLiteral(), "documentation", documentation));
			}
		}
		return literals;
	}

	private static void putDocumentation(Map<String, Object> json, EModelElement element) {
		String documentation = SkailifyAnnotations.description(element);
		if (documentation != null) {
			json.put("documentation", documentation);
		}
	}
}
