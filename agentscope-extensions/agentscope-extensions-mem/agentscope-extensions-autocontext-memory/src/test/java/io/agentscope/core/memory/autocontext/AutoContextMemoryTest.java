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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class AutoContextMemoryTest {

    @Test
    void deleteMessageRemovesOnlyTheWorkingBufferEntry() {
        AutoContextMemory memory = new AutoContextMemory(AutoContextConfig.builder().build(), null);
        Msg first = AutoContextTestSupport.userMessage("first");
        Msg second = AutoContextTestSupport.assistantMessage("second");
        Msg third = AutoContextTestSupport.userMessage("third");

        memory.addMessage(first);
        memory.addMessage(second);
        memory.addMessage(third);

        memory.deleteMessage(1);

        assertEquals(2, memory.getMessages().size());
        assertEquals("first", memory.getMessages().get(0).getTextContent());
        assertEquals("third", memory.getMessages().get(1).getTextContent());
        assertEquals(3, memory.getOriginalMemoryMsgs().size());
        assertEquals("first", memory.getOriginalMemoryMsgs().get(0).getTextContent());
        assertEquals("second", memory.getOriginalMemoryMsgs().get(1).getTextContent());
        assertEquals("third", memory.getOriginalMemoryMsgs().get(2).getTextContent());
    }

    @Test
    void deleteMessageDoesNotDeleteOriginalHistoryAfterCompression() {
        AutoContextMemory memory =
                new AutoContextMemory(
                        AutoContextConfig.builder()
                                .msgThreshold(2)
                                .lastKeep(0)
                                .minCompressionTokenThreshold(1)
                                .build(),
                        AutoContextTestSupport.recordingModel(
                                "compressed", new AtomicReference<>()));
        memory.addMessage(AutoContextTestSupport.userMessage("first"));
        memory.addMessage(AutoContextTestSupport.assistantMessage("second"));

        assertTrue(memory.compressIfNeeded());
        assertEquals(2, memory.getOriginalMemoryMsgs().size());

        memory.deleteMessage(0);

        assertTrue(memory.getMessages().isEmpty());
        assertEquals(2, memory.getOriginalMemoryMsgs().size());
        assertEquals("first", memory.getOriginalMemoryMsgs().get(0).getTextContent());
        assertEquals("second", memory.getOriginalMemoryMsgs().get(1).getTextContent());
    }

    @Test
    void saveAndLoadRestoresSnapshotState() {
        AutoContextMemory memory = new AutoContextMemory(AutoContextConfig.builder().build(), null);
        memory.addMessage(AutoContextTestSupport.userMessage("hello"));
        memory.addMessage(AutoContextTestSupport.assistantMessage("world"));
        memory.offload("offload-1", List.of(AutoContextTestSupport.userMessage("offloaded")));
        memory.getCompressionEvents()
                .add(
                        new CompressionEvent(
                                CompressionEvent.CURRENT_ROUND_MESSAGE_COMPRESS,
                                123L,
                                1,
                                null,
                                null,
                                "compressed-1",
                                java.util.Map.of("tokenBefore", 10, "tokenAfter", 4)));

        var store = AutoContextTestSupport.inMemoryStore();
        memory.saveTo(store, "alice", "session-1");

        AutoContextMemory loaded = new AutoContextMemory(AutoContextConfig.builder().build(), null);
        loaded.loadFrom(store, "alice", "session-1");

        assertEquals(2, loaded.getMessages().size());
        assertEquals("hello", loaded.getMessages().get(0).getTextContent());
        assertEquals("world", loaded.getMessages().get(1).getTextContent());
        assertEquals(2, loaded.getOriginalMemoryMsgs().size());
        assertNotNull(loaded.reload("offload-1"));
        assertEquals(1, loaded.reload("offload-1").size());
        assertEquals("offloaded", loaded.reload("offload-1").get(0).getTextContent());
        assertEquals(1, loaded.getCompressionEvents().size());
        assertEquals(
                CompressionEvent.CURRENT_ROUND_MESSAGE_COMPRESS,
                loaded.getCompressionEvents().get(0).getEventType());
    }

    @Test
    void compressIfNeededAsyncRunsOnBoundedElasticAndSummarizesMessages() {
        AtomicReference<String> threadName = new AtomicReference<>();
        AutoContextMemory memory =
                new AutoContextMemory(
                        AutoContextConfig.builder()
                                .msgThreshold(2)
                                .lastKeep(0)
                                .minCompressionTokenThreshold(1)
                                .build(),
                        AutoContextTestSupport.recordingModel("compressed", threadName));
        memory.addMessage(AutoContextTestSupport.userMessage("first message"));
        memory.addMessage(AutoContextTestSupport.assistantMessage("second message"));

        StepVerifier.create(memory.compressIfNeededAsync().subscribeOn(Schedulers.parallel()))
                .expectNext(true)
                .verifyComplete();

        assertNotNull(threadName.get());
        assertTrue(threadName.get().contains("boundedElastic"));
        assertEquals(1, memory.getMessages().size());
        assertEquals("compressed", memory.getMessages().get(0).getTextContent());
        assertEquals(1, memory.getCompressionEvents().size());
    }

    @Test
    void compressIfNeededDoesNotHoldTheLockWhileTheModelCallIsInFlight() throws Exception {
        CountDownLatch streamStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        Model blockingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        streamStarted.countDown();
                        try {
                            if (!releaseModel.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("timed out waiting for release");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        io.agentscope.core.message.TextBlock
                                                                .builder()
                                                                .text("compressed")
                                                                .build()))
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "blocking";
                    }
                };
        AutoContextMemory memory =
                new AutoContextMemory(
                        AutoContextConfig.builder()
                                .largePayloadThreshold(1)
                                .msgThreshold(2)
                                .lastKeep(0)
                                .minCompressionTokenThreshold(1)
                                .build(),
                        blockingModel);
        memory.addMessage(
                AutoContextTestSupport.userMessage(
                        "first message is large enough to trigger offload"));
        memory.addMessage(AutoContextTestSupport.assistantMessage("second"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> compressionFuture = executor.submit(memory::compressIfNeeded);
            assertTrue(streamStarted.await(1, TimeUnit.SECONDS));

            Future<?> addFuture =
                    executor.submit(
                            () -> memory.addMessage(AutoContextTestSupport.userMessage("third")));
            addFuture.get(500, TimeUnit.MILLISECONDS);

            releaseModel.countDown();
            assertTrue(compressionFuture.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(3, memory.getMessages().size());
        assertEquals("compressed", memory.getMessages().get(0).getTextContent());
        assertEquals("second", memory.getMessages().get(1).getTextContent());
        assertEquals("third", memory.getMessages().get(2).getTextContent());
    }

    @Test
    void builderRejectsInvalidCompressionSettings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().tokenRatio(0.0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().tokenRatio(1.1).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().maxToken(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().lastKeep(-1).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().largePayloadThreshold(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().offloadSinglePreview(-1).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().msgThreshold(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().minConsecutiveToolMessages(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().currentRoundCompressionRatio(1.2).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoContextConfig.builder().minCompressionTokenThreshold(0).build());
    }

    @Test
    void mergeWithContextAppendsTailOrReplacesWorkingBufferOnly() {
        AutoContextMemory memory = new AutoContextMemory(AutoContextConfig.builder().build(), null);
        Msg first = AutoContextTestSupport.userMessage("first");
        Msg second = AutoContextTestSupport.assistantMessage("second");
        Msg third = AutoContextTestSupport.userMessage("third");
        memory.mergeWithContext(List.of(first, second));
        memory.mergeWithContext(List.of(first, second, third));

        assertEquals(3, memory.getMessages().size());
        assertEquals(3, memory.getOriginalMemoryMsgs().size());

        Msg reset = AutoContextTestSupport.assistantMessage("reset");
        memory.mergeWithContext(List.of(reset));

        assertEquals(1, memory.getMessages().size());
        assertEquals("reset", memory.getMessages().get(0).getTextContent());
        assertEquals(3, memory.getOriginalMemoryMsgs().size());
        assertEquals("first", memory.getOriginalMemoryMsgs().get(0).getTextContent());
    }

    @Test
    void compressIfNeededReturnsFalseWhenThresholdsOrCandidatesDoNotMatch() {
        AutoContextMemory empty = new AutoContextMemory(AutoContextConfig.builder().build(), null);
        assertTrue(!empty.compressIfNeeded());

        AutoContextMemory belowThreshold =
                new AutoContextMemory(
                        AutoContextConfig.builder()
                                .msgThreshold(10)
                                .minCompressionTokenThreshold(1)
                                .build(),
                        null);
        belowThreshold.addMessage(AutoContextTestSupport.userMessage("short"));
        belowThreshold.addMessage(AutoContextTestSupport.assistantMessage("tiny"));
        assertTrue(!belowThreshold.compressIfNeeded());
    }

    @Test
    void compressIfNeededHandlesCurrentRoundToolUseAndLargeToolOffload() {
        AutoContextMemory currentRound =
                new AutoContextMemory(
                        AutoContextConfig.builder()
                                .msgThreshold(3)
                                .lastKeep(2)
                                .minCompressionTokenThreshold(1)
                                .build(),
                        AutoContextTestSupport.recordingModel(
                                "compressed round", new AtomicReference<>()));
        currentRound.addMessage(AutoContextTestSupport.userMessage("user"));
        currentRound.addMessage(AutoContextTestSupport.assistantMessage("assistant body"));
        currentRound.addMessage(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                ToolUseBlock.builder()
                                        .id("call-1")
                                        .name("tool")
                                        .input(Map.of("k", "v"))
                                        .build())
                        .build());

        assertTrue(currentRound.compressIfNeeded());
        assertEquals(3, currentRound.getMessages().size());
        assertEquals("user", currentRound.getMessages().get(0).getTextContent());
        assertTrue(currentRound.getMessages().get(1).getTextContent().contains("compressed round"));
        assertTrue(currentRound.getMessages().get(1).getTextContent().contains("CONTEXT_OFFLOAD"));
        assertTrue(currentRound.getMessages().get(2).hasContentBlocks(ToolUseBlock.class));

        AutoContextMemory largeTool =
                new AutoContextMemory(
                        AutoContextConfig.builder()
                                .largePayloadThreshold(1)
                                .msgThreshold(99)
                                .maxToken(1)
                                .tokenRatio(1.0)
                                .lastKeep(0)
                                .minCompressionTokenThreshold(1)
                                .build(),
                        AutoContextTestSupport.recordingModel(
                                "tool summary", new AtomicReference<>()));
        Msg toolMessage =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .name("tool")
                        .content(
                                ToolResultBlock.builder()
                                        .id("call-2")
                                        .name("search")
                                        .output(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("very long payload")
                                                                .build()))
                                        .build())
                        .build();
        largeTool.addMessage(toolMessage);

        assertTrue(largeTool.compressIfNeeded());
        assertEquals(MsgRole.TOOL, largeTool.getMessages().get(0).getRole());
        assertTrue(largeTool.getMessages().get(0).getMetadata().containsKey("_compress_meta"));
    }

    @Test
    void compressIfNeededFallsBackWhenModelUnavailableOrReturnsNoText() {
        AutoContextMemory noModel =
                new AutoContextMemory(
                        AutoContextConfig.builder()
                                .msgThreshold(2)
                                .lastKeep(0)
                                .minCompressionTokenThreshold(1)
                                .build(),
                        null);
        noModel.addMessage(AutoContextTestSupport.userMessage("alpha"));
        noModel.addMessage(AutoContextTestSupport.assistantMessage("beta"));

        assertTrue(noModel.compressIfNeeded());
        assertTrue(noModel.getMessages().get(0).getTextContent().contains("Compressed 2 messages"));

        Model emptyResponseModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(ChatResponse.builder().content(List.of()).build());
                    }

                    @Override
                    public String getModelName() {
                        return "empty";
                    }
                };
        AutoContextMemory blankResponse =
                new AutoContextMemory(
                        AutoContextConfig.builder()
                                .msgThreshold(2)
                                .lastKeep(0)
                                .minCompressionTokenThreshold(1)
                                .build(),
                        emptyResponseModel);
        blankResponse.addMessage(AutoContextTestSupport.userMessage("gamma"));
        blankResponse.addMessage(AutoContextTestSupport.assistantMessage("delta"));

        assertTrue(blankResponse.compressIfNeeded());
        assertTrue(
                blankResponse
                        .getMessages()
                        .get(0)
                        .getTextContent()
                        .contains("Compressed 2 messages"));
    }

    @Test
    void clearAndSetWorkingMessagesResetVisibleBuffersOnly() {
        AutoContextMemory memory = new AutoContextMemory(AutoContextConfig.builder().build(), null);
        memory.addMessage(AutoContextTestSupport.userMessage("a"));
        memory.addMessage(AutoContextTestSupport.assistantMessage("b"));
        memory.offload("uuid", List.of(AutoContextTestSupport.userMessage("offload")));
        memory.clear("uuid");
        assertTrue(memory.reload("uuid").isEmpty());

        memory.setWorkingMessages(List.of(AutoContextTestSupport.assistantMessage("working")));
        assertEquals(1, memory.getMessages().size());
        assertEquals("working", memory.getMessages().get(0).getTextContent());
        assertEquals(2, memory.getOriginalMemoryMsgs().size());

        memory.clear();
        assertTrue(memory.getMessages().isEmpty());
        assertTrue(memory.getOriginalMemoryMsgs().isEmpty());
        assertTrue(memory.getCompressionEvents().isEmpty());
        assertTrue(memory.getOffloadContext().isEmpty());
    }

    @Test
    void restoreHandlesNullSnapshotAndMissingCompressionEvents() {
        AutoContextMemory memory = new AutoContextMemory(AutoContextConfig.builder().build(), null);
        memory.restore(null);
        assertTrue(memory.getMessages().isEmpty());

        AutoContextState snapshot = new AutoContextState();
        snapshot.setWorkingMessages(List.of(AutoContextTestSupport.userMessage("saved")));
        snapshot.setOriginalMessages(List.of(AutoContextTestSupport.userMessage("saved")));
        snapshot.setCompressionEvents(null);
        memory.restore(snapshot);

        assertEquals(1, memory.getMessages().size());
        assertTrue(memory.getCompressionEvents().isEmpty());
        assertNull(memory.getAutoContextConfig().getCustomPrompt());
    }
}
