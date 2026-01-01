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
package io.agentscope.core.util;

import java.util.Optional;

/**
 * Utility class for type-safe operations.
 */
public final class TypeUtils {

    private TypeUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Safely cast an object to the specified type.
     *
     * @param obj The object to cast
     * @param clazz The target class
     * @param <T> The target type
     * @return The casted object
     * @throws ClassCastException if the object is not an instance of the target class
     */
    public static <T> T safeCast(Object obj, Class<T> clazz) {
        if (obj == null) {
            return null;
        }
        if (clazz.isInstance(obj)) {
            return clazz.cast(obj);
        }
        throw new ClassCastException(
                String.format("Cannot cast %s to %s", obj.getClass().getName(), clazz.getName()));
    }

    /**
     * Safely cast an object to the specified type, returning Optional.
     *
     * @param obj The object to cast
     * @param clazz The target class
     * @param <T> The target type
     * @return Optional containing the casted object, or empty if cast fails
     */
    public static <T> Optional<T> safeCastOptional(Object obj, Class<T> clazz) {
        if (!clazz.isInstance(obj)) {
            return Optional.empty();
        }
        return Optional.of(clazz.cast(obj));
    }
}
