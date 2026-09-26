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
package io.agentscope.core.model;

/**
 * Exception contract for model providers that can classify failures caused by unavailable media.
 *
 * <p>This keeps recovery logic provider-neutral while allowing extension modules to preserve
 * provider-specific exception types and diagnostics.
 */
public interface ModelMediaException {

    /**
     * Returns whether the model provider failed because it could not access referenced media.
     *
     * @return true when referenced media is unavailable
     */
    boolean isMediaUnavailable();
}
