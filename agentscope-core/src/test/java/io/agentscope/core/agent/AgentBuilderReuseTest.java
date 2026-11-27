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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.TaskReminderMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentBuilderReuseTest {
    @Test
    void repeatedBuildDoesNotAccumulateGeneratedMiddleware() {
        Model model = mock(Model.class);
        var store = new InMemoryAgentStateStore();
        var builder =
                ReActAgent.builder()
                        .name("shared-builder")
                        .model(model)
                        .stateStore(store)
                        .maxIters(17)
                        .enableTaskList()
                        .skillRepository(mock(AgentSkillRepository.class));
        try (var first = builder.build();
                var second = builder.build()) {
            assertEquals(1, generated(first, DynamicSkillMiddleware.class));
            assertEquals(1, generated(second, DynamicSkillMiddleware.class));
            assertEquals(1, generated(first, TaskReminderMiddleware.class));
            assertEquals(1, generated(second, TaskReminderMiddleware.class));
            assertNotSame(first.getToolkit(), second.getToolkit());
            assertSame(model, second.getModel());
            assertSame(store, second.getStateStore());
            assertEquals(17, second.getMaxIters());
        }
    }

    @Test
    void configurationCopyDoesNotChangeSourceRegistrations() {
        var builder = ReActAgent.builder().name("original").model(mock(Model.class));
        var copy = builder.copy().name("copy").skillRepository(mock(AgentSkillRepository.class));
        try (var original = builder.build();
                var changed = copy.build()) {
            assertEquals("original", original.getName());
            assertEquals("copy", changed.getName());
            assertEquals(0, generated(original, DynamicSkillMiddleware.class));
            assertEquals(1, generated(changed, DynamicSkillMiddleware.class));
        }
    }

    /**
     * {@code copy()} lists the builder's fields by hand, so a field added later and not copied
     * here would silently drop configuration. Every field is therefore given a value that
     * differs from its default, and the copy is checked field by field.
     */
    @Test
    void copyCarriesEveryConfiguredField() throws Exception {
        var builder = ReActAgent.builder().name("source");
        List<Field> fields = new ArrayList<>();
        for (Field field : ReActAgent.Builder.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            fields.add(field);
            if (Collection.class.isAssignableFrom(field.getType())) {
                collection(builder, field).add(mock(elementType(field)));
            } else {
                field.set(builder, sentinel(field, builder));
            }
        }
        assertFalse(fields.isEmpty());
        var copy = builder.copy();
        for (Field field : fields) {
            Object expected = field.get(builder);
            Object actual = field.get(copy);
            if (expected instanceof Collection<?> source) {
                assertEquals(source, actual, field.getName() + " is not copied");
                assertNotSame(source, actual, field.getName() + " is shared with the copy");
            } else {
                assertSame(expected, actual, field.getName() + " is not carried over by copy()");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> collection(Object builder, Field field) throws Exception {
        return (Collection<Object>) field.get(builder);
    }

    private static Class<?> elementType(Field field) {
        return (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
    }

    /** A value that differs from whatever the field holds now, so a missing copy is visible. */
    private static Object sentinel(Field field, Object builder) throws Exception {
        Class<?> type = field.getType();
        if (type == boolean.class) {
            return !field.getBoolean(builder);
        }
        if (type == int.class) {
            return field.getInt(builder) + 1;
        }
        if (type == String.class) {
            return "sentinel-" + field.getName();
        }
        if (type == Integer.class) {
            return 41;
        }
        if (type == Boolean.class) {
            return Boolean.TRUE;
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants[0].equals(field.get(builder)) ? constants[1] : constants[0];
        }
        return mock(type);
    }

    private static long generated(ReActAgent agent, Class<?> type) {
        return agent.getMiddlewares().stream().filter(type::isInstance).count();
    }
}
