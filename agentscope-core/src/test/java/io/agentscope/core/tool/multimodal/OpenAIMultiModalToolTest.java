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
package io.agentscope.core.tool.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.OpenAIClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class OpenAIMultiModalToolTest {

    private OpenAIClient client;
    private OpenAIMultiModalTool tool;

    @BeforeEach
    void setUp() {
        client = mock(OpenAIClient.class);
        tool = new OpenAIMultiModalTool(client);
    }

    @Test
    void testTextToImage_Url() {
        String prompt = "A cute cat";
        String jsonResponse = "{\"data\": [{\"url\": \"https://example.com/cat.png\"}]}";

        when(client.callApi(any(), any(), eq("/v1/images/generations"), any()))
                .thenReturn(jsonResponse);

        Mono<ToolResultBlock> resultMono =
                tool.openaiTextToImage(prompt, "dall-e-3", 1, "1024x1024", "standard", "url");
        ToolResultBlock result = resultMono.block();

        assertNotNull(result);
        List<ContentBlock> content = result.getOutput();
        assertNotNull(content);
        assertEquals(1, content.size());
        assertTrue(content.get(0) instanceof ImageBlock);
        ImageBlock image = (ImageBlock) content.get(0);
        assertTrue(image.getSource() instanceof URLSource);
        assertEquals("https://example.com/cat.png", ((URLSource) image.getSource()).getUrl());
    }
}
