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
package io.agentscope.spring.boot.openaiofficial;

import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import java.util.function.Consumer;

/**
 * Customizer for {@link OpenAIResponsesChatModel.Builder}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 *     @Bean
 *     public OpenAIResponsesChatModelBuilderCustomizer openAIResponsesChatModelBuilderCustomizer() {
 *         return builder -> builder.strictJsonSchema(true);
 *     }
 * }</pre>
 */
@FunctionalInterface
public interface OpenAIResponsesChatModelBuilderCustomizer
        extends Consumer<OpenAIResponsesChatModel.Builder> {

    /**
     * Customize the {@link OpenAIResponsesChatModel.Builder}.
     *
     * @param builder the builder to customize
     */
    void customize(OpenAIResponsesChatModel.Builder builder);

    /**
     * Accept and invoke the given builder.
     *
     * @param builder the builder to customize
     */
    @Override
    default void accept(OpenAIResponsesChatModel.Builder builder) {
        this.customize(builder);
    }
}
