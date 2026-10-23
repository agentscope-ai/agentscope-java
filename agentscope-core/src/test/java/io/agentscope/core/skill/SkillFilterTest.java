/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkillFilterTest {

    // ==================== Standalone filters ====================

    @Test
    void testAll() {
        SkillFilter filter = SkillFilter.all();
        assertTrue(filter.isAllowed("any_skill"));
        assertTrue(filter.isAllowed("another"));
        assertFalse(filter.isOverlay());
    }

    @Test
    void testNone() {
        SkillFilter filter = SkillFilter.none();
        assertFalse(filter.isAllowed("any_skill"));
        assertFalse(filter.isAllowed("another"));
        assertFalse(filter.isOverlay());
    }

    @Test
    void testOnly() {
        SkillFilter filter = SkillFilter.only("a", "b");
        assertTrue(filter.isAllowed("a"));
        assertTrue(filter.isAllowed("b"));
        assertFalse(filter.isAllowed("c"));
        assertFalse(filter.isOverlay());
    }

    @Test
    void testExcept() {
        SkillFilter filter = SkillFilter.except("a", "b");
        assertFalse(filter.isAllowed("a"));
        assertFalse(filter.isAllowed("b"));
        assertTrue(filter.isAllowed("c"));
        assertFalse(filter.isOverlay());
    }

    // ==================== Overlay filters ====================

    @Test
    void testEnable() {
        SkillFilter filter = SkillFilter.enable("a", "b");
        assertTrue(filter.isAllowed("a"));
        assertTrue(filter.isAllowed("b"));
        assertFalse(filter.isAllowed("c"));
        assertTrue(filter.isOverlay());
    }

    @Test
    void testDisable() {
        SkillFilter filter = SkillFilter.disable("a", "b");
        assertFalse(filter.isAllowed("a"));
        assertFalse(filter.isAllowed("b"));
        assertTrue(filter.isAllowed("c"));
        assertTrue(filter.isOverlay());
    }

    // ==================== Overlay merge ====================

    @Test
    void testOverlayNullReturnsBase() {
        SkillFilter base = SkillFilter.only("a", "b");
        SkillFilter result = base.overlay(null);
        assertTrue(result.isAllowed("a"));
        assertTrue(result.isAllowed("b"));
        assertFalse(result.isAllowed("c"));
    }

    @Test
    void testOverlayDisableOnWhitelist() {
        // Builder: only a, b, c
        // Runtime: disable b
        // Result: a and c enabled, b disabled
        SkillFilter base = SkillFilter.only("a", "b", "c");
        SkillFilter runtime = SkillFilter.disable("b");
        SkillFilter merged = base.overlay(runtime);

        assertTrue(merged.isAllowed("a"));
        assertFalse(merged.isAllowed("b"));
        assertTrue(merged.isAllowed("c"));
        assertFalse(merged.isAllowed("d"));
    }

    @Test
    void testOverlayEnableOnNone() {
        // Builder: none
        // Runtime: enable x
        // Result: only x enabled
        SkillFilter base = SkillFilter.none();
        SkillFilter runtime = SkillFilter.enable("x");
        SkillFilter merged = base.overlay(runtime);

        assertTrue(merged.isAllowed("x"));
        assertFalse(merged.isAllowed("y"));
    }

    @Test
    void testOverlayEnableOnAll() {
        // Builder: all
        // Runtime: enable x (no-op since all are already enabled)
        SkillFilter base = SkillFilter.all();
        SkillFilter runtime = SkillFilter.enable("x");
        SkillFilter merged = base.overlay(runtime);

        assertTrue(merged.isAllowed("x"));
        assertTrue(merged.isAllowed("y"));
    }

    @Test
    void testOverlayDisableOnAll() {
        // Builder: all
        // Runtime: disable b
        // Result: everything except b
        SkillFilter base = SkillFilter.all();
        SkillFilter runtime = SkillFilter.disable("b");
        SkillFilter merged = base.overlay(runtime);

        assertTrue(merged.isAllowed("a"));
        assertFalse(merged.isAllowed("b"));
        assertTrue(merged.isAllowed("c"));
    }

    @Test
    void testOverlayEnableOnBlacklist() {
        // Builder: except("a", "b")
        // Runtime: enable("a")  → a is force-enabled by runtime
        // Result: a enabled, b disabled, c enabled
        SkillFilter base = SkillFilter.except("a", "b");
        SkillFilter runtime = SkillFilter.enable("a");
        SkillFilter merged = base.overlay(runtime);

        assertTrue(merged.isAllowed("a"));
        assertFalse(merged.isAllowed("b"));
        assertTrue(merged.isAllowed("c"));
    }

    @Test
    void testOverlayNonOverlayReplacesEntirely() {
        // If runtime passes a standalone filter (not overlay), it replaces the base
        SkillFilter base = SkillFilter.all();
        SkillFilter runtime = SkillFilter.only("x");
        SkillFilter merged = base.overlay(runtime);

        assertTrue(merged.isAllowed("x"));
        assertFalse(merged.isAllowed("a"));
    }

    @Test
    void testMergedFilterIsNotOverlay() {
        SkillFilter merged = SkillFilter.all().overlay(SkillFilter.disable("b"));
        assertFalse(merged.isOverlay());
    }
}
