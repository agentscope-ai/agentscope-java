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

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AgentCallOptionsTest {

    private static final String LEGACY_HOOK_TYPE = "io.agentscope.core.hook.Hook";

    @Test
    void shouldNotExposeLegacyHookTypes() {
        assertFalse(referencesLegacyHook(AgentCallOptions.class));
        assertFalse(referencesLegacyHook(AgentCallOptions.Builder.class));
    }

    private boolean referencesLegacyHook(Class<?> type) {
        Stream<Type> fields =
                Arrays.stream(type.getDeclaredFields()).map(field -> field.getGenericType());
        Stream<Type> methods = Arrays.stream(type.getDeclaredMethods()).flatMap(this::methodTypes);
        return Stream.concat(fields, methods)
                .map(Type::getTypeName)
                .anyMatch(name -> name.contains(LEGACY_HOOK_TYPE));
    }

    private Stream<Type> methodTypes(Method method) {
        return Stream.concat(
                Stream.of(method.getGenericReturnType()),
                Arrays.stream(method.getGenericParameterTypes()));
    }
}
