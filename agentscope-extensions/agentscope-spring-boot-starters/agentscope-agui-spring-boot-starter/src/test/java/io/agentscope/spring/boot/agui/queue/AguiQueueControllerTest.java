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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.queue.MessageQueueRegistry;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AguiQueueControllerTest {

    private MockMvc mockMvc;
    private AguiRequestProcessor requestProcessor;
    private MessageQueueRegistry registry;

    @BeforeEach
    void setUp() {
        requestProcessor = mock(AguiRequestProcessor.class);
        when(requestProcessor.isThreadIdle(anyString())).thenReturn(true);
        when(requestProcessor.isRunActive(anyString())).thenReturn(false);
        when(requestProcessor.isAwaitingConfirmation(anyString())).thenReturn(false);
        // No run has started on the mocked threads unless a test says otherwise.
        when(requestProcessor.lastStartedRunId(anyString())).thenReturn(null);

        registry = new MessageQueueRegistry(2, 64);
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
    void shouldAnswerDuplicateWithSameAcceptance() throws Exception {
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t1", "m1", "hello")))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

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

        assertEquals(0, registry.size());
    }

    @Test
    void shouldRejectMalformedBodyWithBadRequest() throws Exception {
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"threadId\":\"t1\","))
                .andExpect(status().isBadRequest());

        assertEquals(0, registry.size());
    }

    @Test
    void shouldRejectUnparsableJsonWithBadRequest() throws Exception {
        // Jackson raises JsonParseException; the controller must map it to 400 instead of letting
        // it escape as a server error.
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not-json"))
                .andExpect(status().isBadRequest());

        assertEquals(0, registry.size());
    }

    @Test
    void shouldRejectLiteralNullBodyWithBadRequest() throws Exception {
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("null"))
                .andExpect(status().isBadRequest());

        assertEquals(0, registry.size());
    }

    @Test
    void shouldRejectNullMessageElementWithBadRequest() throws Exception {
        // A JSON null inside messages reaches the controller as a null list element; taking its id
        // must not throw a server error.
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":[null]}"))
                .andExpect(status().isBadRequest());

        assertEquals(0, registry.size());
    }

    @Test
    void shouldRejectNullAnywhereInMessages() throws Exception {
        // A null element before a valid trailing message is still a broken body.
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"threadId\":\"t1\",\"runId\":\"r1\","
                                                + "\"messages\":[null,{\"id\":\"m2\","
                                                + "\"role\":\"user\",\"content\":\"second\"}]}"))
                .andExpect(status().isBadRequest());

        assertEquals(0, registry.size());
    }

    @Test
    void shouldKeyOnTheTrailingMessageOfTheBody() throws Exception {
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"threadId\":\"t1\",\"runId\":\"r1\","
                                                + "\"messages\":[{\"id\":\"m1\",\"role\":\"user\","
                                                + "\"content\":\"first\"},{\"id\":\"m2\","
                                                + "\"role\":\"user\",\"content\":\"second\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.messageId").value("m2"));
    }

    @Test
    void shouldAcceptAnEmptyResumeList() throws Exception {
        // Only an answer that actually resumes an interrupt is rejected; an empty list is no
        // answer at all.
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"threadId\":\"t1\",\"runId\":\"r1\","
                                                + "\"messages\":[{\"id\":\"m1\",\"role\":\"user\","
                                                + "\"content\":\"hello\"}],\"resume\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void shouldRejectBodyWithoutMessages() throws Exception {
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectBodyWithTheMessagesFieldAbsent() throws Exception {
        // The parser leaves an omitted field null instead of defaulting it, so acceptance must
        // answer 400 here rather than fail on a null list.
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"threadId\":\"t1\",\"runId\":\"r1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAcceptBodyWithTheResumeFieldAbsent() throws Exception {
        // An omitted resume field is not an interrupt answer, so the body is queued normally.
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t1", "m1", "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void shouldRejectResumeBodies() throws Exception {
        // A thread that waits for a confirmation never becomes idle, so a queued answer could
        // never be claimed; answers belong on the run endpoint.
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"threadId\":\"t1\",\"runId\":\"r1\","
                                                + "\"messages\":[{\"id\":\"m1\",\"role\":\"user\","
                                                + "\"content\":\"hello\"}],"
                                                + "\"resume\":[{\"interruptId\":\"i1\","
                                                + "\"status\":\"resolved\"}]}"))
                .andExpect(status().isBadRequest());

        assertEquals(0, registry.size());
    }

    @Test
    void shouldRejectMessageWithoutId() throws Exception {
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t1", "", "hello")))
                .andExpect(status().isBadRequest());

        assertEquals(0, registry.size());
    }

    @Test
    void shouldReturnSnapshotWithThreadFlags() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));
        // A thread that waits for confirmation is not running.
        when(requestProcessor.isRunActive("t1")).thenReturn(false);
        when(requestProcessor.isThreadIdle("t1")).thenReturn(false);
        when(requestProcessor.isAwaitingConfirmation("t1")).thenReturn(true);

        mockMvc.perform(get("/agui/queue/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.awaitingConfirmation").value(true))
                .andExpect(jsonPath("$.items[0].messageId").value("m1"))
                .andExpect(jsonPath("$.items[0].position").value(1));
    }

    @Test
    void shouldReturnEmptySnapshotWithoutCreatingQueue() throws Exception {
        mockMvc.perform(get("/agui/queue/unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.active").value(false));

        assertEquals(0, registry.size());
    }

    @Test
    void shouldReturnNoContentWhenNothingIsQueued() throws Exception {
        mockMvc.perform(post("/agui/queue/t1/claim")).andExpect(status().isNoContent());
    }

    @Test
    void shouldRejectAMissingJsonContentTypeOnAcceptance() throws Exception {
        // The accept endpoint shares the run endpoint's JSON content-type requirement, so a
        // body without it is rejected before it is parsed.
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content(body("t1", "m1", "hello")))
                .andExpect(status().isUnsupportedMediaType());
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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.messageId").value("m1"))
                .andExpect(jsonPath("$.input.threadId").value("t1"));

        mockMvc.perform(post("/agui/queue/t1/claim")).andExpect(status().isNoContent());

        // The run evidence must be read before the idle state, so a replay that starts between
        // the two reads shows up as busy instead of pairing new evidence with stale idleness.
        InOrder inOrder = inOrder(requestProcessor);
        inOrder.verify(requestProcessor).lastStartedRunId("t1");
        inOrder.verify(requestProcessor).isThreadIdle("t1");
    }

    @Test
    void shouldDeliverAfterTheConfirmationIsAnswered() throws Exception {
        // A thread that waits for a confirmation accepts new messages but delivers none.
        when(requestProcessor.isThreadIdle("t1")).thenReturn(false);
        when(requestProcessor.isAwaitingConfirmation("t1")).thenReturn(true);
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t1", "m1", "queued while waiting")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mockMvc.perform(post("/agui/queue/t1/claim")).andExpect(status().isNoContent());

        // Answering the confirmation unblocks the thread and the message becomes claimable.
        when(requestProcessor.isThreadIdle("t1")).thenReturn(true);
        when(requestProcessor.isAwaitingConfirmation("t1")).thenReturn(false);
        mockMvc.perform(post("/agui/queue/t1/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("m1"));
    }

    @Test
    void shouldClaimBodyBeReplayableOnTheRunEndpoint() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "你好")));

        String claimBody =
                mockMvc.perform(post("/agui/queue/t1/claim"))
                        .andExpect(status().isOk())
                        // The AG-UI content of a text message must stay a JSON string, and the
                        // response must not lose non-ASCII text on the way out.
                        .andExpect(content().contentType("application/json;charset=UTF-8"))
                        .andExpect(jsonPath("$.input.messages[0].content").value("你好"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        ClaimEnvelope envelope = JsonUtils.getJsonCodec().fromJson(claimBody, ClaimEnvelope.class);
        // A client posts the input object back to the run endpoint, so it must survive one more
        // encode/decode pair with the very same parser.
        RunAgentInput replayed =
                new AguiRequestBodyParser()
                        .parse(JsonUtils.getJsonCodec().toJson(envelope.input()));
        assertEquals("你好", replayed.getMessages().get(0).getTextContent());
    }

    @Test
    void shouldClaimWithAFreshRunId() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));

        String claimBody =
                mockMvc.perform(post("/agui/queue/t1/claim"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        ClaimEnvelope envelope = JsonUtils.getJsonCodec().fromJson(claimBody, ClaimEnvelope.class);
        // The queued body reuses the run id of the run that was active when it was accepted; a
        // verbatim replay of that id trips duplicate-request guards, so a claim mints a fresh one.
        assertNotEquals("r1", envelope.input().getRunId());
        assertEquals("m1", envelope.messageId());
    }

    @Test
    void shouldDeliverRightAfterTheReplayedRunEndsWithoutPollingClaim() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m2", "second")));
        String claimBody =
                mockMvc.perform(post("/agui/queue/t1/claim"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.messageId").value("m1"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String replayedRunId =
                JsonUtils.getJsonCodec()
                        .fromJson(claimBody, ClaimEnvelope.class)
                        .input()
                        .getRunId();

        // The claimed message is replayed verbatim; that run starts and finishes between the two
        // claims and the client never polls claim during the replay. The thread's last started run
        // being the very id this delivery handed out is the evidence that the hand-off completed,
        // so the next claim delivers immediately instead of waiting out the five-second guard.
        when(requestProcessor.lastStartedRunId("t1")).thenReturn(replayedRunId);

        mockMvc.perform(post("/agui/queue/t1/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("m2"));
    }

    @Test
    void shouldNotDeliverWhileTheReplayedRunIsStillActive() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m2", "second")));
        String replayedRunId =
                JsonUtils.getJsonCodec()
                        .fromJson(
                                mockMvc.perform(post("/agui/queue/t1/claim"))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString(),
                                ClaimEnvelope.class)
                        .input()
                        .getRunId();

        // The matching run id releases the guard, but the replayed run is still active so the
        // thread is not idle: the next claim must hold the message instead of delivering into a
        // busy thread.
        when(requestProcessor.lastStartedRunId("t1")).thenReturn(replayedRunId);
        when(requestProcessor.isThreadIdle("t1")).thenReturn(false);

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

    @Test
    void shouldAcknowledgeARetryAfterWithdrawAsADuplicate() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));
        mockMvc.perform(delete("/agui/queue/t1/m1")).andExpect(status().isNoContent());

        // A retry that reaches the server after a lost withdrawal response is acknowledged as a
        // duplicate of the withdrawn message instead of being queued and executed again.
        mockMvc.perform(
                        post("/agui/queue/t1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("t1", "m1", "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.state").value("WITHDRAWN"))
                .andExpect(jsonPath("$.position").value(-1));
    }

    @Test
    void shouldRejectWithdrawOfClaimedMessage() throws Exception {
        mockMvc.perform(
                post("/agui/queue/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "m1", "hello")));
        mockMvc.perform(post("/agui/queue/t1/claim")).andExpect(status().isOk());

        mockMvc.perform(delete("/agui/queue/t1/m1")).andExpect(status().isConflict());
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

    /** Mirrors the claim response so the test replays exactly what a client would replay. */
    private record ClaimEnvelope(String messageId, RunAgentInput input) {}
}
