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
package io.agentscope.core.agui.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-visible {@link AguiJsonPatch} RFC 6902 applier.
 */
class AguiJsonPatchTest {

    @Test
    void addReplacesMissingKey() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.add("/key", "value")));

        assertEquals("value", result.get("key"));
    }

    @Test
    void replaceOverwritesExistingValue() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("key", "old");

        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.replace("/key", "new")));

        assertEquals("new", result.get("key"));
    }

    @Test
    void removeDeletesKey() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("key", "value");

        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.remove("/key")));

        assertTrue(!result.containsKey("key"));
    }

    @Test
    void nestedPointersTraverseMaps() {
        Map<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("inner", "old");
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("nested", inner);

        Map<String, Object> result =
                AguiJsonPatch.apply(
                        target, List.of(JsonPatchOperation.replace("/nested/inner", "new")));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) result.get("nested");
        assertEquals("new", nested.get("inner"));
    }

    @Test
    void tildeIsUnescaped() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();

        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.add("/key~0with", "value")));

        assertEquals("value", result.get("key~with"));
    }

    @Test
    void slashIsUnescaped() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();

        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.add("/key~1with", "value")));

        assertEquals("value", result.get("key/with"));
    }

    @Test
    void addToListWithAppendToken() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("items", new java.util.ArrayList<>(List.of(1, 2, 3)));

        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.add("/items/-", 4)));

        assertEquals(List.of(1, 2, 3, 4), result.get("items"));
    }

    @Test
    void addToListAtIndexInserts() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("items", new java.util.ArrayList<>(List.of(1, 2, 3)));

        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.add("/items/1", 9)));

        assertEquals(List.of(1, 9, 2, 3), result.get("items"));
    }

    @Test
    void replaceListItem() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("items", new java.util.ArrayList<>(List.of(1, 2, 3)));

        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.replace("/items/0", 7)));

        assertEquals(List.of(7, 2, 3), result.get("items"));
    }

    @Test
    void removeListItem() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("items", new java.util.ArrayList<>(List.of(1, 2, 3)));

        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.remove("/items/1")));

        assertEquals(List.of(1, 3), result.get("items"));
    }

    @Test
    void unknownOpIsIgnored() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("key", "value");

        Map<String, Object> result =
                AguiJsonPatch.apply(
                        target, List.of(new JsonPatchOperation("move", "/key", null, "/other")));

        assertEquals("value", result.get("key"));
    }

    @Test
    void doesNotMutateInput() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("key", "old");

        Map<String, Object> result =
                AguiJsonPatch.apply(target, List.of(JsonPatchOperation.replace("/key", "new")));

        assertNotSame(target, result);
        assertEquals("old", target.get("key"));
    }

    @Test
    void multipleOpsApplyInOrder() {
        Map<String, Object> target = new java.util.LinkedHashMap<>();

        Map<String, Object> result =
                AguiJsonPatch.apply(
                        target,
                        List.of(
                                JsonPatchOperation.add("/a", 1),
                                JsonPatchOperation.add("/b", 2),
                                JsonPatchOperation.remove("/a")));

        assertTrue(!result.containsKey("a"));
        assertEquals(2, result.get("b"));
    }
}
