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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * A call whose subscriber cancels (e.g. an SSE client disconnecting) while the end-of-call state
 * save is already running on {@code boundedElastic}: cancellation cannot stop that blocking write,
 * but the per-session call gate is released immediately, so the next same-session call is admitted
 * while the cancelled call still owns the session state.
 */
@DisplayName("ReActAgent cancelled call vs. next same-session call")
class ReActAgentCancelledCallStateWriteTest {

    private static final class EchoModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "echo";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String text = "answer-" + calls.incrementAndGet();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                            .build());
        }
    }

    /**
     * File-backed store whose next save can be held open, like a slow database write. The held
     * write ignores thread interrupts, as a JDBC statement already sent to the server would.
     */
    private static final class SlowSaveStore extends JsonFileAgentStateStore {
        private final AtomicBoolean holdNextSave = new AtomicBoolean();
        private final CountDownLatch saveEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSave = new CountDownLatch(1);

        SlowSaveStore(Path root) {
            super(root);
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            if ("agent_state".equals(key) && holdNextSave.compareAndSet(true, false)) {
                saveEntered.countDown();
                boolean interrupted = false;
                while (releaseSave.getCount() > 0) {
                    try {
                        releaseSave.await(10, TimeUnit.SECONDS);
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                try {
                    super.save(userId, sessionId, key, value);
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return;
            }
            super.save(userId, sessionId, key, value);
        }
    }

    @Test
    @DisplayName("a cancelled call's in-flight save must not overwrite the next call's turn")
    void cancelledCallsInFlightSaveDoesNotOverwriteNextCall(@TempDir Path dir) throws Exception {
        SlowSaveStore store = new SlowSaveStore(dir);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new EchoModel())
                        .stateStore(store)
                        .build();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("s1").build();

        // Call A: its end-of-call save blocks; the subscriber cancels while it is in flight.
        store.holdNextSave.set(true);
        Disposable callA =
                agent.call(List.of(userMsg("question-1")), ctx)
                        .subscribeOn(Schedulers.parallel())
                        .subscribe();
        assertTrue(store.saveEntered.await(5, TimeUnit.SECONDS), "call A should reach its save");
        callA.dispose();

        // Call B on the same session. The gate should keep it waiting until A's write is done.
        CompletableFuture<Msg> callB =
                agent.call(List.of(userMsg("question-2")), ctx)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        Thread.sleep(1000);
        boolean bFinishedWhileAStillWriting = callB.isDone();

        // A's blocked write now lands.
        store.releaseSave.countDown();
        callB.get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        assertFalse(
                bFinishedWhileAStillWriting,
                "call B ran to completion while cancelled call A was still writing the same"
                        + " session's state");
        AgentState persisted =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("hi")
                        .model(new EchoModel())
                        .stateStore(store)
                        .build()
                        .getAgentState("u1", "s1");
        List<String> texts = persisted.getContext().stream().map(Msg::getTextContent).toList();
        assertTrue(
                texts.contains("question-2"),
                "call B completed, so its turn must survive; persisted context was " + texts);
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
