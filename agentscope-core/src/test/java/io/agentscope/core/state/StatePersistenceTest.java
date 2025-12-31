/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for StatePersistence. */
@DisplayName("StatePersistence Tests")
class StatePersistenceTest {

    @Nested
    @DisplayName("Static Factory Methods")
    class StaticFactoryMethods {

        @Test
        @DisplayName("all() should enable all components")
        void testAllFactory() {
            StatePersistence persistence = StatePersistence.all();
            assertTrue(persistence.memoryManaged());
            assertTrue(persistence.toolkitManaged());
            assertTrue(persistence.planNotebookManaged());
            assertTrue(persistence.statefulToolsManaged());
        }

        @Test
        @DisplayName("none() should disable all components")
        void testNoneFactory() {
            StatePersistence persistence = StatePersistence.none();
            assertFalse(persistence.memoryManaged());
            assertFalse(persistence.toolkitManaged());
            assertFalse(persistence.planNotebookManaged());
            assertFalse(persistence.statefulToolsManaged());
        }

        @Test
        @DisplayName("memoryOnly() should enable only memory")
        void testMemoryOnlyFactory() {
            StatePersistence persistence = StatePersistence.memoryOnly();
            assertTrue(persistence.memoryManaged());
            assertFalse(persistence.toolkitManaged());
            assertFalse(persistence.planNotebookManaged());
            assertFalse(persistence.statefulToolsManaged());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Builder should have all components enabled by default")
        void testBuilderDefaults() {
            StatePersistence persistence = StatePersistence.builder().build();
            assertTrue(persistence.memoryManaged());
            assertTrue(persistence.toolkitManaged());
            assertTrue(persistence.planNotebookManaged());
            assertTrue(persistence.statefulToolsManaged());
        }

        @Test
        @DisplayName("Builder should allow disabling memory management")
        void testBuilderDisableMemory() {
            StatePersistence persistence = StatePersistence.builder().memoryManaged(false).build();
            assertFalse(persistence.memoryManaged());
            assertTrue(persistence.toolkitManaged());
            assertTrue(persistence.planNotebookManaged());
            assertTrue(persistence.statefulToolsManaged());
        }

        @Test
        @DisplayName("Builder should allow disabling toolkit management")
        void testBuilderDisableToolkit() {
            StatePersistence persistence = StatePersistence.builder().toolkitManaged(false).build();
            assertTrue(persistence.memoryManaged());
            assertFalse(persistence.toolkitManaged());
            assertTrue(persistence.planNotebookManaged());
            assertTrue(persistence.statefulToolsManaged());
        }

        @Test
        @DisplayName("Builder should allow disabling planNotebook management")
        void testBuilderDisablePlanNotebook() {
            StatePersistence persistence =
                    StatePersistence.builder().planNotebookManaged(false).build();
            assertTrue(persistence.memoryManaged());
            assertTrue(persistence.toolkitManaged());
            assertFalse(persistence.planNotebookManaged());
            assertTrue(persistence.statefulToolsManaged());
        }

        @Test
        @DisplayName("Builder should allow disabling statefulTools management")
        void testBuilderDisableStatefulTools() {
            StatePersistence persistence =
                    StatePersistence.builder().statefulToolsManaged(false).build();
            assertTrue(persistence.memoryManaged());
            assertTrue(persistence.toolkitManaged());
            assertTrue(persistence.planNotebookManaged());
            assertFalse(persistence.statefulToolsManaged());
        }

        @Test
        @DisplayName("Builder should allow disabling all components")
        void testBuilderDisableAll() {
            StatePersistence persistence =
                    StatePersistence.builder()
                            .memoryManaged(false)
                            .toolkitManaged(false)
                            .planNotebookManaged(false)
                            .statefulToolsManaged(false)
                            .build();
            assertFalse(persistence.memoryManaged());
            assertFalse(persistence.toolkitManaged());
            assertFalse(persistence.planNotebookManaged());
            assertFalse(persistence.statefulToolsManaged());
        }

        @Test
        @DisplayName("Builder should support method chaining")
        void testBuilderChaining() {
            StatePersistence persistence =
                    StatePersistence.builder()
                            .memoryManaged(true)
                            .toolkitManaged(false)
                            .planNotebookManaged(true)
                            .statefulToolsManaged(false)
                            .build();
            assertTrue(persistence.memoryManaged());
            assertFalse(persistence.toolkitManaged());
            assertTrue(persistence.planNotebookManaged());
            assertFalse(persistence.statefulToolsManaged());
        }
    }

    @Nested
    @DisplayName("Record Functionality")
    class RecordFunctionality {

        @Test
        @DisplayName("Records with same values should be equal")
        void testEquality() {
            StatePersistence p1 = StatePersistence.all();
            StatePersistence p2 = StatePersistence.all();
            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
        }

        @Test
        @DisplayName("Constructor should accept all boolean values")
        void testConstructor() {
            StatePersistence persistence = new StatePersistence(true, false, true, false);
            assertTrue(persistence.memoryManaged());
            assertFalse(persistence.toolkitManaged());
            assertTrue(persistence.planNotebookManaged());
            assertFalse(persistence.statefulToolsManaged());
        }
    }
}
