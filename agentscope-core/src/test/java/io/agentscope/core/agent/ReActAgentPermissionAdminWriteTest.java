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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Regression tests for issue #2535: {@code setPermissionMode} / {@code replacePermissionContext}
 * read the never-refreshed in-process {@code stateCache} entry and write the whole {@link
 * AgentState} back. Two agents sharing one store model a multi-replica deployment, exactly like
 * {@code ReActAgentPerSessionStateTest#clearContextReloadsPersistedStateBeforeClearingConversation}.
 *
 * <p>Store note: {@code InMemoryAgentStateStore} keeps {@link AgentState} instances by reference,
 * so a reload can return the very instance that was mutated in place — it cannot model the
 * serialize/deserialize round-trip of the production MySQL/Redis stores. Tests that assert
 * "the never-persisted local mutation must not reach the store" therefore run against {@code
 * JsonFileAgentStateStore}, whose file round-trip yields a fresh instance per load.
 */
@DisplayName("ReActAgent permission admin writes reload authoritative state")
class ReActAgentPermissionAdminWriteTest {

    private static final class NoopModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "noop";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    private ReActAgent agent(AgentStateStore store) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("hi")
                .model(new NoopModel())
                .stateStore(store)
                .build();
    }

    @Test
    @DisplayName("setPermissionMode reloads the store first and must not revert newer state")
    void setPermissionModeReloadsStoreBeforeWriting() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent staleAgent = agent(store);
        staleAgent.getAgentState("u1", "sessA"); // pod A caches v1

        // pod B advances the persisted session while pod A's cache goes stale
        ReActAgent writerAgent = agent(store);
        AgentState latest = writerAgent.getAgentState("u1", "sessA");
        latest.contextMutable().add(userMsg("latest context"));
        latest.setSummary("latest summary");
        writerAgent.saveAgentState("u1", "sessA");

        staleAgent.setPermissionMode("u1", "sessA", PermissionMode.BYPASS);

        ReActAgent reborn = agent(store);
        AgentState restored = reborn.getAgentState("u1", "sessA");
        assertTrue(
                allText(restored).contains("latest context"),
                "a permission toggle on one replica must not truncate the newer persisted"
                        + " conversation; restored context was: "
                        + allText(restored));
        assertEquals("latest summary", restored.getSummary());
        assertEquals(PermissionMode.BYPASS, restored.getPermissionContext().getMode());
    }

    @Test
    @DisplayName("replacePermissionContext reloads the store first and must not revert newer state")
    void replacePermissionContextReloadsStoreBeforeWriting() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent staleAgent = agent(store);
        staleAgent.getAgentState("u1", "sessA"); // pod A caches v1

        ReActAgent writerAgent = agent(store);
        AgentState latest = writerAgent.getAgentState("u1", "sessA");
        latest.contextMutable().add(userMsg("latest context"));
        latest.setSummary("latest summary");
        writerAgent.saveAgentState("u1", "sessA");

        staleAgent.replacePermissionContext(
                "u1",
                "sessA",
                PermissionContextState.builder().mode(PermissionMode.BYPASS).build());

        ReActAgent reborn = agent(store);
        AgentState restored = reborn.getAgentState("u1", "sessA");
        assertTrue(
                allText(restored).contains("latest context"),
                "replacing the permission context must not truncate the newer persisted"
                        + " conversation; restored context was: "
                        + allText(restored));
        assertEquals("latest summary", restored.getSummary());
        assertEquals(PermissionMode.BYPASS, restored.getPermissionContext().getMode());
    }

    @Test
    @DisplayName("setPermissionMode must not promote an unpersisted failed-call state")
    void setPermissionModeDoesNotPromoteUnpersistedFailedCallState(@TempDir Path tempDir) {
        // JsonFileAgentStateStore round-trips through serialization like the production
        // MySQL/Redis stores, so a reload returns a fresh instance rather than aliasing the
        // same in-process object the InMemory store would return by reference.
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        ReActAgent agent = agent(store);
        AgentState cached = agent.getAgentState("u1", "sessA");
        cached.contextMutable().add(userMsg("clean turn"));
        agent.saveAgentState("u1", "sessA"); // store holds the clean state

        // Simulate a call that failed mid-flight: it mutated the cached instance (dangling
        // tool_use with no tool_result) but never persisted — that must stay unpersisted.
        cached.contextMutable().add(danglingToolUseAssistantMsg());

        agent.setPermissionMode("u1", "sessA", PermissionMode.BYPASS);

        ReActAgent reborn = agent(store);
        AgentState restored = reborn.getAgentState("u1", "sessA");
        assertEquals(
                1,
                restored.getContext().size(),
                "the never-persisted failed-call tail (dangling tool_use) must not be promoted"
                        + " into the store by a permission toggle; restored context was: "
                        + allText(restored));
        assertEquals(PermissionMode.BYPASS, restored.getPermissionContext().getMode());
    }

    @Test
    @DisplayName("replacePermissionContext must not promote an unpersisted failed-call state")
    void replacePermissionContextDoesNotPromoteUnpersistedFailedCallState(@TempDir Path tempDir) {
        // Sibling of the setPermissionMode poison test: both entry points share the reload
        // helper today; this guards against them diverging if one is reimplemented.
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        ReActAgent agent = agent(store);
        AgentState cached = agent.getAgentState("u1", "sessA");
        cached.contextMutable().add(userMsg("clean turn"));
        agent.saveAgentState("u1", "sessA");
        cached.contextMutable().add(danglingToolUseAssistantMsg());

        agent.replacePermissionContext(
                "u1",
                "sessA",
                PermissionContextState.builder().mode(PermissionMode.BYPASS).build());

        ReActAgent reborn = agent(store);
        AgentState restored = reborn.getAgentState("u1", "sessA");
        assertEquals(
                1,
                restored.getContext().size(),
                "the never-persisted failed-call tail must not be promoted into the store by a"
                        + " permission context replacement; restored context was: "
                        + allText(restored));
        assertEquals(PermissionMode.BYPASS, restored.getPermissionContext().getMode());
    }

    @Test
    @DisplayName("an in-flight cached instance keeps receiving the mode change")
    void inFlightInstanceKeepsReceivingTheModeChange() {
        // In-flight calls hold the previously cached instance; their end-of-call save must not
        // revert the toggle, so the permission context has to be applied to that instance too.
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent = agent(store);
        AgentState inFlight = agent.getAgentState("u1", "sessA");

        agent.setPermissionMode("u1", "sessA", PermissionMode.BYPASS);

        assertEquals(
                PermissionMode.BYPASS,
                inFlight.getPermissionContext().getMode(),
                "the instance held by an in-flight call must observe the new mode so its"
                        + " end-of-call save cannot revert the toggle");
    }

    @Test
    @DisplayName("an in-flight cached instance keeps receiving a replaced permission context")
    void inFlightInstanceKeepsReceivingReplacedPermissionContext() {
        // Mirror guard for the second entry point sharing the mirror helper.
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent = agent(store);
        AgentState inFlight = agent.getAgentState("u1", "sessA");

        agent.replacePermissionContext(
                "u1",
                "sessA",
                PermissionContextState.builder().mode(PermissionMode.BYPASS).build());

        assertEquals(
                PermissionMode.BYPASS,
                inFlight.getPermissionContext().getMode(),
                "the instance held by an in-flight call must observe the replaced context so"
                        + " its end-of-call save cannot revert it");
    }

    @Test
    @DisplayName("the in-flight mirror happens before the admin save (OVERWRITE gap closed)")
    void inFlightMirrorPrecedesTheAdminSave(@TempDir Path tempDir) {
        // If the mirror ran after the admin save, an in-flight end-of-call save racing inside
        // that gap would hit a CAS conflict under the default OVERWRITE policy and persist the
        // previous instance without the new context — silently swallowing the admin change.
        // This test observes the in-flight instance's mode at the moment the admin save fires.
        AtomicReference<AgentState> inFlightRef = new AtomicReference<>();
        AtomicReference<PermissionMode> modeAtAdminSave = new AtomicReference<>();
        ReActAgent agent = agent(observingStore(tempDir, inFlightRef, modeAtAdminSave));
        AgentState inFlight = agent.getAgentState("u1", "sessA");
        inFlightRef.set(inFlight);
        inFlight.contextMutable().add(userMsg("in-flight turn"));
        agent.saveAgentState("u1", "sessA"); // persisted once; the reload below gets a fresh copy

        agent.setPermissionMode("u1", "sessA", PermissionMode.BYPASS);

        assertNotNull(modeAtAdminSave.get(), "the admin save must have been observed");
        assertEquals(
                PermissionMode.BYPASS,
                modeAtAdminSave.get(),
                "by the time the admin save persists the reloaded state, the previously cached"
                        + " instance must already carry the new mode — otherwise an in-flight"
                        + " end-save racing between the save and the mirror could OVERWRITE the"
                        + " store without it");
    }

    @Test
    @DisplayName("the in-flight mirror for replacePermissionContext also precedes the admin save")
    void replacePermissionContextMirrorPrecedesTheAdminSave(@TempDir Path tempDir) {
        // Sibling of inFlightMirrorPrecedesTheAdminSave for the second entry point sharing
        // installPermissionContext; guards against the two orderings diverging on refactor.
        AtomicReference<AgentState> inFlightRef = new AtomicReference<>();
        AtomicReference<PermissionMode> modeAtAdminSave = new AtomicReference<>();
        ReActAgent agent = agent(observingStore(tempDir, inFlightRef, modeAtAdminSave));
        AgentState inFlight = agent.getAgentState("u1", "sessA");
        inFlightRef.set(inFlight);
        inFlight.contextMutable().add(userMsg("in-flight turn"));
        agent.saveAgentState("u1", "sessA");

        agent.replacePermissionContext(
                "u1",
                "sessA",
                PermissionContextState.builder().mode(PermissionMode.BYPASS).build());

        assertNotNull(modeAtAdminSave.get(), "the admin save must have been observed");
        assertEquals(
                PermissionMode.BYPASS,
                modeAtAdminSave.get(),
                "by the time the admin save persists the reloaded state, the previously cached"
                        + " instance must already carry the replaced context");
    }

    @Test
    @DisplayName("without a store the permission toggle still mutates the cached state in place")
    void withoutStorePermissionToggleMutatesCachedState() {
        ReActAgent agent =
                ReActAgent.builder().name("asst").sysPrompt("hi").model(new NoopModel()).build();
        AgentState cached = agent.getAgentState("u1", "sessA");

        agent.setPermissionMode("u1", "sessA", PermissionMode.BYPASS);

        assertSame(cached, agent.getAgentState("u1", "sessA"));
        assertEquals(PermissionMode.BYPASS, cached.getPermissionContext().getMode());
    }

    @Test
    @DisplayName("without a store replacing the permission context still mutates the cached state")
    void withoutStoreReplacePermissionContextMutatesCachedState() {
        // Sibling guard for the second entry point on the no-store path.
        ReActAgent agent =
                ReActAgent.builder().name("asst").sysPrompt("hi").model(new NoopModel()).build();
        AgentState cached = agent.getAgentState("u1", "sessA");

        agent.replacePermissionContext(
                "u1",
                "sessA",
                PermissionContextState.builder().mode(PermissionMode.BYPASS).build());

        assertSame(cached, agent.getAgentState("u1", "sessA"));
        assertEquals(PermissionMode.BYPASS, cached.getPermissionContext().getMode());
    }

    /**
     * A {@link JsonFileAgentStateStore} that records the watched in-flight instance's permission
     * mode at the moment any {@code agent_state} save targeting a different instance fires (the
     * admin save persists the reloaded copy, never the in-flight instance itself).
     */
    private static AgentStateStore observingStore(
            Path tempDir,
            AtomicReference<AgentState> inFlightRef,
            AtomicReference<PermissionMode> modeAtAdminSave) {
        return new JsonFileAgentStateStore(tempDir) {
            @Override
            public void save(String u, String s, String key, State value) {
                AgentState inFlight = inFlightRef.get();
                if ("agent_state".equals(key) && inFlight != null && value != inFlight) {
                    modeAtAdminSave.set(inFlight.getPermissionContext().getMode());
                }
                super.save(u, s, key, value);
            }
        };
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** An assistant turn carrying a tool_use block with no matching tool_result. */
    private static Msg danglingToolUseAssistantMsg() {
        return Msg.builder()
                .name("asst")
                .role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id("tc-dangling").name("some_tool").build())
                .build();
    }

    /** Text content only — non-text blocks (e.g. tool_use) are deliberately not represented. */
    private static List<String> allText(AgentState state) {
        List<String> out = new ArrayList<>();
        for (Msg m : state.getContext()) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof TextBlock t) {
                    out.add(t.getText());
                }
            }
        }
        return out;
    }
}
