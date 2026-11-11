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
package de.dim.skailify.mcp.provider;

import static de.dim.skailify.mcp.provider.Requests.request;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

class PreviewAuthorizationTest {

	private static HttpServletRequest with(Map<String, String> headers) {
		return PreviewAuthorization.of(request("https", "app.example", "", headers));
	}

	@Test
	void thePreviewHeaderStandsInForAMissingAuthorization() {
		HttpServletRequest request = with(Map.of(PreviewAuthorization.HEADER, "Bearer app-token", "Accept", "application/json"));

		assertThat(request.getHeader("Authorization")).isEqualTo("Bearer app-token");
		assertThat(request.getHeader("authorization")).isEqualTo("Bearer app-token");
		assertThat(Collections.list(request.getHeaders("Authorization"))).containsExactly("Bearer app-token");
		assertThat(Collections.list(request.getHeaderNames())).contains("Authorization", "Accept");
		assertThat(request.getHeader("Accept")).isEqualTo("application/json");
	}

	@Test
	void aRealAuthorizationWins() {
		HttpServletRequest original = request("https", "app.example", "",
				Map.of("Authorization", "Bearer real", PreviewAuthorization.HEADER, "Bearer preview"));

		assertThat(PreviewAuthorization.of(original)).isSameAs(original);
		assertThat(PreviewAuthorization.of(original).getHeader("Authorization")).isEqualTo("Bearer real");
	}

	@Test
	void withoutEitherHeaderNothingChanges() {
		HttpServletRequest original = request("https", "app.example", "", Map.of());
		assertThat(PreviewAuthorization.of(original)).isSameAs(original);
		HttpServletRequest blank = request("https", "app.example", "", Map.of(PreviewAuthorization.HEADER, " "));
		assertThat(PreviewAuthorization.of(blank)).isSameAs(blank);
	}
}
