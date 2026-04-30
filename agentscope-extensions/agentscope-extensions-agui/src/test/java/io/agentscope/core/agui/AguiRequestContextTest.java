/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AguiRequestContext}.
 */
class AguiRequestContextTest {

    @AfterEach
    void cleanUp() {
        AguiRequestContext.clear();
    }

    // ---- current() before init ----

    @Test
    void current_withoutInit_returnsEmptyContext() {
        AguiRequestContext context = AguiRequestContext.current();

        assertNotNull(context);
        assertNull(context.getHeader("Any-Header"));
        assertNull(context.getParameter("anyParam"));
        assertTrue(context.getAllHeaders().isEmpty());
        assertTrue(context.getAllParameters().isEmpty());
    }

    @Test
    void current_withoutInit_neverReturnsNull() {
        assertNotNull(AguiRequestContext.current());
    }

    // ---- init() and current() ----

    @Test
    void init_andCurrent_providesHeadersAndParams() {
        Map<String, List<String>> headers = Map.of("X-User-Id", List.of("user-42"));
        Map<String, List<String>> params = Map.of("tenantId", List.of("tenant-1"));

        AguiRequestContext.init(headers, params);
        AguiRequestContext context = AguiRequestContext.current();

        assertEquals("user-42", context.getHeader("X-User-Id"));
        assertEquals("tenant-1", context.getParameter("tenantId"));
    }

    @Test
    void init_withNullHeaders_treatsAsEmpty() {
        AguiRequestContext.init(null, Map.of("key", List.of("value")));
        AguiRequestContext context = AguiRequestContext.current();

        assertTrue(context.getAllHeaders().isEmpty());
        assertEquals("value", context.getParameter("key"));
    }

    @Test
    void init_withNullParams_treatsAsEmpty() {
        AguiRequestContext.init(Map.of("X-Token", List.of("abc")), null);
        AguiRequestContext context = AguiRequestContext.current();

        assertEquals("abc", context.getHeader("X-Token"));
        assertTrue(context.getAllParameters().isEmpty());
    }

    // ---- Header access ----

    @Test
    void getHeader_caseInsensitive_findsHeaderRegardlessOfCase() {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/json"));
        AguiRequestContext.init(headers, Collections.emptyMap());
        AguiRequestContext context = AguiRequestContext.current();

        assertEquals("application/json", context.getHeader("content-type"));
        assertEquals("application/json", context.getHeader("CONTENT-TYPE"));
        assertEquals("application/json", context.getHeader("Content-Type"));
    }

    @Test
    void getHeader_withMultipleValues_returnsFirstValue() {
        Map<String, List<String>> headers =
                Map.of("Accept", Arrays.asList("text/html", "application/json"));
        AguiRequestContext.init(headers, Collections.emptyMap());

        assertEquals("text/html", AguiRequestContext.current().getHeader("Accept"));
    }

    @Test
    void getHeader_withMissingHeader_returnsNull() {
        AguiRequestContext.init(Collections.emptyMap(), Collections.emptyMap());

        assertNull(AguiRequestContext.current().getHeader("X-Missing"));
    }

    @Test
    void getHeaders_returnsAllValuesForHeader() {
        Map<String, List<String>> headers = Map.of("X-Roles", Arrays.asList("admin", "user"));
        AguiRequestContext.init(headers, Collections.emptyMap());

        List<String> roles = AguiRequestContext.current().getHeaders("X-Roles");
        assertEquals(2, roles.size());
        assertTrue(roles.contains("admin"));
        assertTrue(roles.contains("user"));
    }

    @Test
    void getHeaders_withMissingHeader_returnsEmptyList() {
        AguiRequestContext.init(Collections.emptyMap(), Collections.emptyMap());

        List<String> values = AguiRequestContext.current().getHeaders("X-Missing");
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }

    @Test
    void getAllHeaders_returnsUnmodifiableMap() {
        Map<String, List<String>> headers = Map.of("X-Custom", List.of("val"));
        AguiRequestContext.init(headers, Collections.emptyMap());

        Map<String, List<String>> allHeaders = AguiRequestContext.current().getAllHeaders();
        assertThrows(
                UnsupportedOperationException.class,
                () -> allHeaders.put("New-Header", List.of("val")));
    }

    // ---- Parameter access ----

    @Test
    void getParameter_withPresentParam_returnsFirstValue() {
        Map<String, List<String>> params = Map.of("page", Arrays.asList("2", "3"));
        AguiRequestContext.init(Collections.emptyMap(), params);

        assertEquals("2", AguiRequestContext.current().getParameter("page"));
    }

    @Test
    void getParameter_withMissingParam_returnsNull() {
        AguiRequestContext.init(Collections.emptyMap(), Collections.emptyMap());

        assertNull(AguiRequestContext.current().getParameter("missing"));
    }

    @Test
    void getParameters_returnsAllValuesForParam() {
        Map<String, List<String>> params = Map.of("tag", Arrays.asList("java", "spring"));
        AguiRequestContext.init(Collections.emptyMap(), params);

        List<String> tags = AguiRequestContext.current().getParameters("tag");
        assertEquals(2, tags.size());
    }

    @Test
    void getParameters_withMissingParam_returnsEmptyList() {
        AguiRequestContext.init(Collections.emptyMap(), Collections.emptyMap());

        List<String> values = AguiRequestContext.current().getParameters("missing");
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }

    @Test
    void getAllParameters_returnsUnmodifiableMap() {
        Map<String, List<String>> params = Map.of("q", List.of("search"));
        AguiRequestContext.init(Collections.emptyMap(), params);

        Map<String, List<String>> allParams = AguiRequestContext.current().getAllParameters();
        assertThrows(
                UnsupportedOperationException.class, () -> allParams.put("new", List.of("val")));
    }

    // ---- clear() ----

    @Test
    void clear_removesContextFromCurrentThread() {
        AguiRequestContext.init(Map.of("X-User", List.of("123")), Collections.emptyMap());
        assertNotNull(AguiRequestContext.current().getHeader("X-User"));

        AguiRequestContext.clear();

        assertNull(AguiRequestContext.current().getHeader("X-User"));
    }

    @Test
    void clear_afterClear_currentReturnsEmptyContext() {
        AguiRequestContext.init(Map.of("X-Token", List.of("abc")), Map.of("p", List.of("v")));
        AguiRequestContext.clear();
        AguiRequestContext context = AguiRequestContext.current();

        assertNull(context.getHeader("X-Token"));
        assertNull(context.getParameter("p"));
        assertTrue(context.getAllHeaders().isEmpty());
        assertTrue(context.getAllParameters().isEmpty());
    }

    // ---- Thread isolation ----

    @Test
    void init_isThreadLocal_doesNotLeakToOtherThreads() throws InterruptedException {
        AguiRequestContext.init(Map.of("X-Thread", List.of("main")), Collections.emptyMap());

        String[] otherThreadHeaderValue = {null};
        Thread otherThread =
                new Thread(
                        () -> {
                            otherThreadHeaderValue[0] =
                                    AguiRequestContext.current().getHeader("X-Thread");
                        });
        otherThread.start();
        otherThread.join();

        // Other thread should not see main thread's context
        assertNull(otherThreadHeaderValue[0]);
        // Main thread should still have its context
        assertEquals("main", AguiRequestContext.current().getHeader("X-Thread"));
    }
}
