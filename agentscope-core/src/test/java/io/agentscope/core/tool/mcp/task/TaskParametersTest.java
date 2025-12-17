/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.tool.mcp.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskParametersTest {

    @Test
    void testConstructor_WithTtl() {
        Long ttl = 60000L;
        TaskParameters params = new TaskParameters(ttl);

        assertEquals(ttl, params.getTtl());
    }

    @Test
    void testConstructor_NullTtl() {
        TaskParameters params = new TaskParameters(null);

        assertNull(params.getTtl());
    }

    @Test
    void testWithTtl_FactoryMethod() {
        Long ttl = 30000L;
        TaskParameters params = TaskParameters.withTtl(ttl);

        assertEquals(ttl, params.getTtl());
    }

    @Test
    void testDefaults_FactoryMethod() {
        TaskParameters params = TaskParameters.defaults();

        assertNull(params.getTtl());
    }

    @Test
    void testEquals_SameParameters() {
        TaskParameters params1 = TaskParameters.withTtl(60000L);
        TaskParameters params2 = TaskParameters.withTtl(60000L);

        assertEquals(params1, params2);
        assertEquals(params1.hashCode(), params2.hashCode());
    }

    @Test
    void testEquals_DifferentTtl() {
        TaskParameters params1 = TaskParameters.withTtl(60000L);
        TaskParameters params2 = TaskParameters.withTtl(30000L);

        assertNotEquals(params1, params2);
    }

    @Test
    void testEquals_BothNull() {
        TaskParameters params1 = TaskParameters.defaults();
        TaskParameters params2 = TaskParameters.defaults();

        assertEquals(params1, params2);
        assertEquals(params1.hashCode(), params2.hashCode());
    }

    @Test
    void testEquals_OneNullOneTtl() {
        TaskParameters params1 = TaskParameters.defaults();
        TaskParameters params2 = TaskParameters.withTtl(60000L);

        assertNotEquals(params1, params2);
    }

    @Test
    void testToString_WithTtl() {
        TaskParameters params = TaskParameters.withTtl(60000L);
        String toString = params.toString();

        assertTrue(toString.contains("60000"));
    }

    @Test
    void testToString_NullTtl() {
        TaskParameters params = TaskParameters.defaults();
        String toString = params.toString();

        assertTrue(toString.contains("null"));
    }
}
