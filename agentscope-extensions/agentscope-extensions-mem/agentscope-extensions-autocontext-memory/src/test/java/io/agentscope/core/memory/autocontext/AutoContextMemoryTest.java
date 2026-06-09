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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class AutoContextMemoryTest {

    @Test
    void deleteMessageRemovesTheIndexedMessageFromBothBuffers() {
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
        assertEquals(2, memory.getOriginalMemoryMsgs().size());
        assertEquals("first", memory.getOriginalMemoryMsgs().get(0).getTextContent());
        assertEquals("third", memory.getOriginalMemoryMsgs().get(1).getTextContent());
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
}
