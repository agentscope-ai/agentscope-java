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
package io.agentscope.spring.boot.openai;

import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.function.Consumer;

/**
 * Customizer for {@link OpenAIChatModel.Builder}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 *     @Bean
 *     public OpenAIChatModelBuilderCustomizer openAIChatModelBuilderCustomizer() {
 *         return builder -> builder.baseUrl("https://custom.example.com/v1");
 *     }
 * }</pre>
 */
@FunctionalInterface
public interface OpenAIChatModelBuilderCustomizer extends Consumer<OpenAIChatModel.Builder> {

    /**
     * Customize the {@link OpenAIChatModel.Builder}.
     *
     * @param builder the builder to customize
     */
    void customize(OpenAIChatModel.Builder builder);

    /**
     * Accept and invoke the given builder.
     *
     * @param builder the builder to customize
     */
    @Override
    default void accept(OpenAIChatModel.Builder builder) {
        this.customize(builder);
    }
}
