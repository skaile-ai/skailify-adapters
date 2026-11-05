/**
 * Copyright (c) 2026 Data In Motion and others.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.dim.skailify.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a tool or app action declares about consent, for the grant catalogue of the Skailify
 * manifest ({@code /.well-known/skailify.json}): whether it only reads, its effect class, whether
 * a person should approve it by default, the OAuth scope it needs, and refinements per model type
 * where a change to one type weighs more than to another.
 * <p>
 * Draft: the vocabulary follows the effect classes of skaile-ai/platform#9032 and the ADRs in
 * skaile-ai/platform#9020 and is aligned with them once they settle.
 *
 * @param name the tool or action name the agent sees
 * @param kind {@value #QUERY} or {@value #EFFECT}
 * @param effectClass {@value #ROUTINE}, {@value #EXTERNAL} or {@value #PRIVILEGED}
 * @param approval {@value #NONE}, {@value #OPTIONAL} or {@value #REQUIRED}
 * @param scope the OAuth scope a caller needs, or null
 * @param description what it does, or null
 * @param types refinements per type, possibly empty
 */
public record ToolGrant(String name, String kind, String effectClass, String approval, String scope,
		String description, List<TypeGrant> types) {

	public static final String QUERY = "query";
	public static final String EFFECT = "effect";

	public static final String ROUTINE = "routine";
	public static final String EXTERNAL = "external";
	public static final String PRIVILEGED = "privileged";
	public static final Set<String> EFFECT_CLASSES = Set.of(ROUTINE, EXTERNAL, PRIVILEGED);

	public static final String NONE = "none";
	public static final String OPTIONAL = "optional";
	public static final String REQUIRED = "required";
	public static final Set<String> APPROVALS = Set.of(NONE, OPTIONAL, REQUIRED);

	/**
	 * What changing objects of one type means
	 *
	 * @param type the type name
	 * @param grant the name of the grant that covers it, or null
	 * @param effectClass its effect class
	 * @param approval its approval default
	 * @param description why, or null
	 */
	public record TypeGrant(String type, String grant, String effectClass, String approval, String description) {

		public TypeGrant {
			check(effectClass, EFFECT_CLASSES, "effect class");
			check(approval, APPROVALS, "approval");
		}

		public Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("type", type);
			putIfSet(map, "grant", grant);
			map.put("effectClass", effectClass);
			map.put("approval", approval);
			putIfSet(map, "description", description);
			return map;
		}
	}

	public ToolGrant {
		if (!QUERY.equals(kind) && !EFFECT.equals(kind)) {
			throw new IllegalArgumentException("kind must be query or effect: " + kind);
		}
		check(effectClass, EFFECT_CLASSES, "effect class");
		check(approval, APPROVALS, "approval");
		types = types == null ? List.of() : List.copyOf(types);
	}

	/** The grant of a tool that only reads: routine, no approval */
	public static ToolGrant query(String name, String scope, String description) {
		return new ToolGrant(name, QUERY, ROUTINE, NONE, scope, description, List.of());
	}

	/** The same grant under another name */
	public ToolGrant named(String newName) {
		return new ToolGrant(newName, kind, effectClass, approval, scope, description, types);
	}

	/** The catalogue entry, in the manifest's JSON shape */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", name);
		map.put("kind", kind);
		map.put("effectClass", effectClass);
		map.put("approval", approval);
		putIfSet(map, "scope", scope);
		putIfSet(map, "description", description);
		if (!types.isEmpty()) {
			map.put("types", types.stream().map(TypeGrant::toMap).toList());
		}
		return map;
	}

	private static void check(String value, Set<String> allowed, String what) {
		if (!allowed.contains(value)) {
			throw new IllegalArgumentException(what + " must be one of " + allowed + ": " + value);
		}
	}

	private static void putIfSet(Map<String, Object> map, String key, String value) {
		if (value != null && !value.isBlank()) {
			map.put(key, value);
		}
	}
}
