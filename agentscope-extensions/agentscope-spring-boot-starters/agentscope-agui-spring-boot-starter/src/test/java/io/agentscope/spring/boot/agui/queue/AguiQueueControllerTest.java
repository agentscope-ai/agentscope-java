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
package io.agentscope.spring.boot.agui.queue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.queue.MessageQueueRegistry;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AguiQueueControllerTest {

    private MockMvc mockMvc;
    private AguiRequestProcessor requestProcessor;

    @BeforeEach
    void setUp() {
        requestProcessor = mock(AguiRequestProcessor.class);
        when(requestProcessor.isThreadIdle(anyString())).thenReturn(true);
        when(requestProcessor.isAwaitingConfirmation(anyString())).thenReturn(false);

        MessageQueueRegistry registry = new MessageQueueRegistry(2, 64);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new AguiQueueController(
                                        registry, requestProcessor, new AguiRequestBodyParser()))
                        .build();
    }

    @Test
    void shouldAcceptAndReturnQueuePosition() throws Exception {
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t1", "m1", "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.messageId").value("m1"))
                .andExpect(jsonPath("$.position").value(1));
    }

    @Test
    void shouldAnswerDuplicateWithSameAcception() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));

        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t1", "m1", "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.position").value(1));
    }

    @Test
    void shouldRejectChangedContentForSameId() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));

        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t1", "m1", "changed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CONFLICT"));
    }

    @Test
    void shouldRejectWhenFull() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "a")));
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m2", "b")));

        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t1", "m3", "c")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("FULL"));
    }

    @Test
    void shouldRejectThreadIdMismatch() throws Exception {
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t2", "m1", "hello")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnSnapshotWithThreadFlags() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));
        when(requestProcessor.isThreadIdle("t1")).thenReturn(false);
        when(requestProcessor.isAwaitingConfirmation("t1")).thenReturn(true);

        mockMvc.perform(get("/agui/queue/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.awaitingConfirmation").value(true))
                .andExpect(jsonPath("$.items[0].messageId").value("m1"))
                .andExpect(jsonPath("$.items[0].position").value(1));
    }

    @Test
    void shouldClaimOnlyWhenIdle() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));

        when(requestProcessor.isThreadIdle("t1")).thenReturn(false);
        mockMvc.perform(post("/agui/queue/t1/claim")).andExpect(status().isNoContent());

        when(requestProcessor.isThreadIdle("t1")).thenReturn(true);
        mockMvc.perform(post("/agui/queue/t1/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("m1"))
                .andExpect(jsonPath("$.input.threadId").value("t1"));

        mockMvc.perform(post("/agui/queue/t1/claim")).andExpect(status().isNoContent());
    }

    @Test
    void shouldWithdrawQueuedMessage() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));

        mockMvc.perform(delete("/agui/queue/t1/m1")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/agui/queue/t1/m1")).andExpect(status().isNotFound());
    }

    private static String body(String threadId, String messageId, String text) {
        return "{\"threadId\":\""
                + threadId
                + "\",\"runId\":\"r1\",\"messages\":[{\"id\":\""
                + messageId
                + "\",\"role\":\"user\",\"content\":\""
                + text
                + "\"}]}";
    }
}
