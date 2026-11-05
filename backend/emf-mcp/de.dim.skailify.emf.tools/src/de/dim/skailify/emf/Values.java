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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.util.EcoreUtil;

/** JSON values (strings, numbers, booleans) as instances of an EDataType. */
final class Values {

	private static final Set<Class<?>> INTEGRAL = Set.of(int.class, long.class, short.class, byte.class, Integer.class,
			Long.class, Short.class, Byte.class, BigInteger.class);

	private Values() {
	}

	/**
	 * The value as an instance of the type: enums by literal or name (or value), other types
	 * through their EDataType's string form.
	 *
	 * @throws IllegalArgumentException with what the agent should give instead
	 */
	static Object convert(EDataType type, Object value) {
		if (type instanceof EEnum eEnum) {
			EEnumLiteral literal = null;
			if (value instanceof String text) {
				literal = eEnum.getEEnumLiteral(text.strip());
				if (literal == null) {
					literal = eEnum.getEEnumLiteralByLiteral(text.strip());
				}
			} else if (value instanceof Number number) {
				literal = eEnum.getEEnumLiteral(number.intValue());
			}
			if (literal == null) {
				throw new IllegalArgumentException("not a " + eEnum.getName() + "; one of "
						+ eEnum.getELiterals().stream().map(EEnumLiteral::getLiteral).toList());
			}
			return literal.getInstance();
		}
		if (value instanceof Map || value instanceof Collection) {
			throw new IllegalArgumentException("expects a single " + type.getName() + " value");
		}
		Class<?> instanceClass = type.getInstanceClass();
		if (value instanceof Boolean && (instanceClass == boolean.class || instanceClass == Boolean.class)) {
			return value;
		}
		try {
			String text;
			if (value instanceof Number number) {
				BigDecimal decimal = new BigDecimal(number.toString());
				text = INTEGRAL.contains(instanceClass) ? decimal.toBigIntegerExact().toString() : decimal.toPlainString();
			} else {
				text = String.valueOf(value);
			}
			if (instanceClass != String.class) {
				text = text.strip();
			}
			return EcoreUtil.createFromString(type, text);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("not a valid " + type.getName() + ": " + value + hint(type));
		}
	}

	private static String hint(EDataType type) {
		Class<?> instanceClass = type.getInstanceClass();
		if (instanceClass == null) {
			return "";
		}
		return switch (instanceClass.getName()) {
		case "java.time.LocalDate" -> " (YYYY-MM-DD)";
		case "java.util.Date" -> " (ISO date-time, e.g. 2026-10-27T12:00:00.000+0000)";
		case "java.math.BigDecimal" -> " (decimal, e.g. 119.00)";
		case "java.lang.Boolean", "boolean" -> " (true or false)";
		default -> "";
		};
	}
}
