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
import java.util.List;
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

    @Test
    void testCreateDeltaWithBothNullMaps() {
        StateDeltaEvent delta = converter.createDelta(null, null, "thread-1", "run-1");

        assertNull(delta); // No changes between two nulls
    }

    @Test
    void testCreateDeltaWithNullBeforeMap() {
        Map<String, Object> after = Map.of("key", "value");

        StateDeltaEvent delta = converter.createDelta(null, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());
        assertEquals("add", delta.getDelta().get(0).getOp());
    }

    @Test
    void testCreateDeltaWithNullAfterMap() {
        Map<String, Object> before = Map.of("key", "value");

        StateDeltaEvent delta = converter.createDelta(before, null, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());
        assertEquals("remove", delta.getDelta().get(0).getOp());
    }

    @Test
    void testHasChangesWithNullMaps() {
        assertFalse(converter.hasChanges(null, null));
        assertTrue(converter.hasChanges(null, Map.of("key", "value")));
        assertTrue(converter.hasChanges(Map.of("key", "value"), null));
    }

    @Test
    void testCreateDeltaWithMultipleChanges() {
        Map<String, Object> before = new HashMap<>();
        before.put("unchanged", "same");
        before.put("toRemove", "old");
        before.put("toReplace", "oldValue");

        Map<String, Object> after = new HashMap<>();
        after.put("unchanged", "same");
        after.put("toReplace", "newValue");
        after.put("toAdd", "new");

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(3, delta.getDelta().size());

        // Verify we have add, remove, and replace operations
        long addCount = delta.getDelta().stream().filter(op -> "add".equals(op.getOp())).count();
        long removeCount =
                delta.getDelta().stream().filter(op -> "remove".equals(op.getOp())).count();
        long replaceCount =
                delta.getDelta().stream().filter(op -> "replace".equals(op.getOp())).count();

        assertEquals(1, addCount);
        assertEquals(1, removeCount);
        assertEquals(1, replaceCount);
    }

    @Test
    void testCreateDeltaWithDeeplyNestedChanges() {
        Map<String, Object> level3Before = new HashMap<>();
        level3Before.put("deepKey", "oldDeep");
        Map<String, Object> level2Before = new HashMap<>();
        level2Before.put("level3", level3Before);
        Map<String, Object> before = new HashMap<>();
        before.put("level2", level2Before);

        Map<String, Object> level3After = new HashMap<>();
        level3After.put("deepKey", "newDeep");
        Map<String, Object> level2After = new HashMap<>();
        level2After.put("level3", level3After);
        Map<String, Object> after = new HashMap<>();
        after.put("level2", level2After);

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());
        assertEquals("/level2/level3/deepKey", delta.getDelta().get(0).getPath());
        assertEquals("replace", delta.getDelta().get(0).getOp());
    }

    @Test
    void testJsonPointerEscapingTilde() {
        Map<String, Object> before = new HashMap<>();
        Map<String, Object> after = new HashMap<>();
        after.put("key~with~tildes", "value");

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        // Per RFC 6901, ~ should be escaped as ~0
        assertTrue(delta.getDelta().get(0).getPath().contains("~0"));
    }

    @Test
    void testJsonPointerEscapingSlash() {
        Map<String, Object> before = new HashMap<>();
        Map<String, Object> after = new HashMap<>();
        after.put("key/with/slashes", "value");

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        // Per RFC 6901, / should be escaped as ~1
        assertTrue(delta.getDelta().get(0).getPath().contains("~1"));
    }

    @Test
    void testCreateSnapshotWithNullState() {
        StateSnapshotEvent snapshot =
                converter.createSnapshot((Map<String, Object>) null, "thread-1", "run-1");

        assertNotNull(snapshot);
        assertTrue(snapshot.getSnapshot().isEmpty());
    }

    @Test
    void testCreateSnapshotWithEmptyState() {
        StateSnapshotEvent snapshot =
                converter.createSnapshot(new HashMap<>(), "thread-1", "run-1");

        assertNotNull(snapshot);
        assertTrue(snapshot.getSnapshot().isEmpty());
    }

    @Test
    void testCreateSnapshotWithComplexState() {
        Map<String, Object> state = new HashMap<>();
        state.put("string", "value");
        state.put("number", 42);
        state.put("boolean", true);
        state.put("nested", Map.of("inner", "data"));
        state.put("list", List.of(1, 2, 3));

        StateSnapshotEvent snapshot = converter.createSnapshot(state, "thread-1", "run-1");

        assertNotNull(snapshot);
        assertEquals(5, snapshot.getSnapshot().size());
        assertEquals("value", snapshot.getSnapshot().get("string"));
        assertEquals(42, snapshot.getSnapshot().get("number"));
    }

    @Test
    void testCreateDeltaWithListValueChange() {
        Map<String, Object> before = Map.of("items", List.of(1, 2, 3));
        Map<String, Object> after = Map.of("items", List.of(1, 2, 3, 4));

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());
        assertEquals("replace", delta.getDelta().get(0).getOp());
    }

    @Test
    void testCreateDeltaWithTypeChange() {
        Map<String, Object> before = Map.of("value", "string");
        Map<String, Object> after = Map.of("value", 123);

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());
        assertEquals("replace", delta.getDelta().get(0).getOp());
        assertEquals(123, delta.getDelta().get(0).getValue());
    }

    @Test
    void testHasChangesWithEmptyMaps() {
        assertFalse(converter.hasChanges(new HashMap<>(), new HashMap<>()));
    }

    @Test
    void testCreateDeltaNestedMapToNonMap() {
        Map<String, Object> before = new HashMap<>();
        before.put("key", Map.of("inner", "value"));

        Map<String, Object> after = new HashMap<>();
        after.put("key", "simple string");

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());
        assertEquals("replace", delta.getDelta().get(0).getOp());
        assertEquals("/key", delta.getDelta().get(0).getPath());
    }

    @Test
    void testCreateDeltaNonMapToNestedMap() {
        Map<String, Object> before = new HashMap<>();
        before.put("key", "simple string");

        Map<String, Object> after = new HashMap<>();
        after.put("key", Map.of("inner", "value"));

        StateDeltaEvent delta = converter.createDelta(before, after, "thread-1", "run-1");

        assertNotNull(delta);
        assertEquals(1, delta.getDelta().size());
        assertEquals("replace", delta.getDelta().get(0).getOp());
    }
}
