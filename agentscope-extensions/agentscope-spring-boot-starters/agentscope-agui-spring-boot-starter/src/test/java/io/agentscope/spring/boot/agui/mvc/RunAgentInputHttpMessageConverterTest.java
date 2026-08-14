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
package io.agentscope.spring.boot.agui.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunAgentInputHttpMessageConverterTest {

    private static final String REQUEST_BODY =
            """
            {
              "threadId": "thread-1",
              "runId": "run-1",
              "messages": [
                {
                  "id": "message-1",
                  "role": "user",
                  "content": "Hello"
                }
              ],
              "tools": []
            }
            """;

    private final RunAgentInputHttpMessageConverter converter =
            new RunAgentInputHttpMessageConverter();

    @Test
    void shouldDeserializeRunAgentInputWithTextMessage() throws Exception {
        MockHttpInputMessage message =
                new MockHttpInputMessage(REQUEST_BODY.getBytes(StandardCharsets.UTF_8));
        message.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        RunAgentInput input = (RunAgentInput) converter.read(RunAgentInput.class, message);

        assertEquals("thread-1", input.getThreadId());
        assertEquals("run-1", input.getRunId());
        assertEquals(1, input.getMessages().size());
        MessageContent.Text content =
                assertInstanceOf(
                        MessageContent.Text.class, input.getMessages().get(0).getContent());
        assertEquals("Hello", content.value());
    }

    @Test
    void shouldOnlyReadRunAgentInput() {
        assertTrue(converter.canRead(RunAgentInput.class, MediaType.APPLICATION_JSON));
        assertFalse(converter.canRead(String.class, MediaType.APPLICATION_JSON));
        assertFalse(converter.canWrite(RunAgentInput.class, MediaType.APPLICATION_JSON));
    }

    @Test
    void shouldBindRunAgentInputForAgentIdEndpoint() throws Exception {
        AguiMvcController aguiMvcController = mock(AguiMvcController.class);
        when(aguiMvcController.handleWithAgentId(
                        any(RunAgentInput.class),
                        isNull(),
                        eq("test-agent"),
                        any(HttpServletRequest.class)))
                .thenReturn(new SseEmitter());

        HttpMessageConverters.ServerBuilder messageConverters =
                HttpMessageConverters.forServer().registerDefaults();
        WebMvcConfigurer configurer =
                new AgentscopeAguiMvcAutoConfiguration().aguiRunAgentInputWebMvcConfigurer();
        configurer.configureMessageConverters(messageConverters);
        HttpMessageConverter<?>[] converterArray =
                StreamSupport.stream(messageConverters.build().spliterator(), false)
                        .toArray(HttpMessageConverter<?>[]::new);

        MockMvc mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new AguiRestController(aguiMvcController, "/agui", true))
                        .addPlaceholderValue("agentscope.agui.path-prefix", "/agui")
                        .addPlaceholderValue("agentscope.agui.agent-id-header", "X-Agent-Id")
                        .setMessageConverters(converterArray)
                        .build();

        mockMvc.perform(
                        post("/agui/run/test-agent")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        ArgumentCaptor<RunAgentInput> inputCaptor = ArgumentCaptor.forClass(RunAgentInput.class);
        verify(aguiMvcController)
                .handleWithAgentId(
                        inputCaptor.capture(),
                        isNull(),
                        eq("test-agent"),
                        any(HttpServletRequest.class));
        assertEquals("thread-1", inputCaptor.getValue().getThreadId());
        assertInstanceOf(
                MessageContent.Text.class,
                inputCaptor.getValue().getMessages().get(0).getContent());
    }
}
