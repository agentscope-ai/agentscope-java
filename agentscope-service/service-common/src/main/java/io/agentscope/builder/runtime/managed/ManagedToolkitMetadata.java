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
package io.agentscope.builder.runtime.managed;

import io.agentscope.core.tool.Toolkit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Read-only compatibility probe for SDK builder metadata; unknown layouts keep the SDK path. */
final class ManagedToolkitMetadata {
    private static final Access ACCESS = access();

    private ManagedToolkitMetadata() {}

    static boolean hasNoCustomArguments(Toolkit toolkit, String name) {
        if (ACCESS == null) return false;
        try {
            Object registry = ACCESS.registry().get(toolkit);
            Object registered = ACCESS.lookup().invoke(registry, name);
            return registered != null
                    && ACCESS.extendedModel().invoke(registered) == null
                    && ACCESS.presets().invoke(registered) instanceof Map<?, ?> presets
                    && presets.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException incompatible) {
            return false;
        }
    }

    private static Access access() {
        try {
            Field registry = Toolkit.class.getDeclaredField("toolRegistry");
            Method lookup = registry.getType().getDeclaredMethod("getRegisteredTool", String.class);
            Method presets = lookup.getReturnType().getDeclaredMethod("getPresetParameters");
            Method extendedModel = lookup.getReturnType().getDeclaredMethod("getExtendedModel");
            registry.setAccessible(true);
            lookup.setAccessible(true);
            presets.setAccessible(true);
            extendedModel.setAccessible(true);
            return new Access(registry, lookup, presets, extendedModel);
        } catch (ReflectiveOperationException | RuntimeException incompatible) {
            return null;
        }
    }

    private record Access(Field registry, Method lookup, Method presets, Method extendedModel) {}
}
