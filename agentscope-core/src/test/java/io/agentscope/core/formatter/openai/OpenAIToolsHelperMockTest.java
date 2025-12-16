/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.openai;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class OpenAIToolsHelperMockTest {

    private OpenAIToolsHelper helper;
    private ChatCompletionCreateParams.Builder mockBuilder;

    @BeforeEach
    void setUp() {
        helper = new OpenAIToolsHelper();
        mockBuilder = mock(ChatCompletionCreateParams.Builder.class);
        // Ensure builder methods return the builder for chaining if needed
        when(mockBuilder.temperature(org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(mockBuilder);
        when(mockBuilder.seed(anyInt())).thenReturn(mockBuilder);
    }

    @Test
    void testApplyOptions_SetsSeed() {
        long seedValue = 12345L;
        GenerateOptions options = GenerateOptions.builder().seed(seedValue).build();

        helper.applyOptions(mockBuilder, options, null, getter -> getter.apply(options));

        verify(mockBuilder).seed((int) seedValue);
    }
}
