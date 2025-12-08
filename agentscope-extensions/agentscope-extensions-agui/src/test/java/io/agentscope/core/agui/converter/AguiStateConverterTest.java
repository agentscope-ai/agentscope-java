/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.agui.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.StateDeltaEvent;
import io.agentscope.core.agui.event.StateDeltaEvent.JsonPatchOperation;
import io.agentscope.core.agui.event.StateSnapshotEvent;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AguiStateConverter.
 */
class AguiStateConverterTest {

    private AguiStateConverter converter;

    @BeforeEach
    void setUp() {
        converter = new AguiStateConverter();
    }

    @Test
    void testCreateSnapshot() {
        Map<String, Object> state = Map.of("key1", "value1", "key2", 42);

        StateSnapshotEvent snapshot = converter.createSnapshot(state, "thread-1", "run-1");

        assertEquals("thread-1", snapshot.getThreadId());
        assertEquals("run-1", snapshot.getRunId());
        assertEquals("value1", snapshot.getSnapshot().get("key1"));
        assertEquals(42, snapshot.getSnapshot().get("key2"));
    }

    @Test
    void testHasChangesReturnsTrueForDifferentMaps() {
        Map<String, Object> before = Map.of("key1", "value1");
        Map<String, Object> after = Map.of("key1", "value2");

        assertTrue(converter.hasChanges(before, after));
    }

    @Test
    void testHasChangesReturnsFalseForIdenticalMaps() {
        Map<String, Object> before = Map.of("key1", "value1");
        Map<String, Object> after = Map.of("key1", "value1");

        assertFalse(converter.hasChanges(before, after));
    }

    @Test
    void testCreateDeltaForAddedKey() {
        Map<String, Object> before = new HashMap<>();
        Map<String, Object> after = Map.of("newKey", "newValue");

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());

        JsonPatchOperation op = delta.getDelta().get(0);
        assertEquals("add", op.getOp());
        assertEquals("/newKey", op.getPath());
        assertEquals("newValue", op.getValue());
    }

    @Test
    void testCreateDeltaForRemovedKey() {
        Map<String, Object> before = Map.of("oldKey", "oldValue");
        Map<String, Object> after = new HashMap<>();

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());

        JsonPatchOperation op = delta.getDelta().get(0);
        assertEquals("remove", op.getOp());
        assertEquals("/oldKey", op.getPath());
    }

    @Test
    void testCreateDeltaForReplacedValue() {
        Map<String, Object> before = Map.of("key", "oldValue");
        Map<String, Object> after = Map.of("key", "newValue");

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());

        JsonPatchOperation op = delta.getDelta().get(0);
        assertEquals("replace", op.getOp());
        assertEquals("/key", op.getPath());
        assertEquals("newValue", op.getValue());
    }

    @Test
    void testCreateDeltaReturnsNullForNoChanges() {
        Map<String, Object> before = Map.of("key", "value");
        Map<String, Object> after = Map.of("key", "value");

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNull(delta);
    }

    @Test
    void testCreateDeltaForNestedChanges() {
        Map<String, Object> nestedBefore = new HashMap<>();
        nestedBefore.put("inner", "oldValue");
        Map<String, Object> before = new HashMap<>();
        before.put("nested", nestedBefore);

        Map<String, Object> nestedAfter = new HashMap<>();
        nestedAfter.put("inner", "newValue");
        Map<String, Object> after = new HashMap<>();
        after.put("nested", nestedAfter);

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());

        JsonPatchOperation op = delta.getDelta().get(0);
        assertEquals("replace", op.getOp());
        assertEquals("/nested/inner", op.getPath());
        assertEquals("newValue", op.getValue());
    }

    @Test
    void testJsonPatchOperationFactoryMethods() {
        JsonPatchOperation add = JsonPatchOperation.add("/path", "value");
        assertEquals("add", add.getOp());
        assertEquals("/path", add.getPath());
        assertEquals("value", add.getValue());

        JsonPatchOperation remove = JsonPatchOperation.remove("/path");
        assertEquals("remove", remove.getOp());
        assertEquals("/path", remove.getPath());

        JsonPatchOperation replace = JsonPatchOperation.replace("/path", "newValue");
        assertEquals("replace", replace.getOp());
        assertEquals("/path", replace.getPath());
        assertEquals("newValue", replace.getValue());
    }
}
