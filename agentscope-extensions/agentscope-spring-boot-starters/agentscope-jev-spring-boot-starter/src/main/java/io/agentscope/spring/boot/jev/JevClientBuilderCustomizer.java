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
package io.agentscope.spring.boot.jev;

import io.agentscope.extensions.judge.jev.JevClient;
import java.util.function.Consumer;

/** Callback for customizing the {@link JevClient.Builder} after properties are applied. */
@FunctionalInterface
public interface JevClientBuilderCustomizer extends Consumer<JevClient.Builder> {

    /** Applies customizations to the Jev client builder. */
    void customize(JevClient.Builder builder);

    /** Accepts and customizes the given Jev client builder. */
    @Override
    default void accept(JevClient.Builder builder) {
        customize(builder);
    }
}
