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
package io.agentscope.core.legacy.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.SessionKey;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Read-only {@link Memory} adapter that proxies {@link #getMessages()} to a live
 * {@link AgentState#getContext()} resolved at call time.
 *
 * <p>Exists solely so deprecated 1.x hooks and tools (notably
 * {@link StaticLongTermMemoryHook}) keep working after the 2.0 builder dropped agent-owned
 * {@code Memory}. All mutating operations throw {@link UnsupportedOperationException}; mutate
 * context via {@code AgentState.context} instead.
 *
 * <p>The {@link Supplier} contract is "best-effort": a {@code null} return is interpreted as
 * "agent not yet bound" and surfaces as an empty message list rather than an exception, so
 * callers may instantiate the view before the owning {@link io.agentscope.core.ReActAgent} is
 * constructed.
 *
 * @deprecated since 2.0.0. New code should read context directly from
 *     {@link AgentState#getContext()}.
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class AgentStateMemoryView implements Memory {

    private final Supplier<AgentState> stateSupplier;

    public AgentStateMemoryView(Supplier<AgentState> stateSupplier) {
        this.stateSupplier = Objects.requireNonNull(stateSupplier, "stateSupplier");
    }

    @Override
    public List<Msg> getMessages() {
        AgentState state = stateSupplier.get();
        return state == null ? List.of() : List.copyOf(state.getContext());
    }

    @Override
    public void addMessage(Msg message) {
        throw readOnly();
    }

    @Override
    public void deleteMessage(int index) {
        throw readOnly();
    }

    @Override
    public void clear() {
        throw readOnly();
    }

    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        // No-op: persistence is owned by AgentState + Session binding, not by this view.
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        throw readOnly();
    }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException(
                "AgentStateMemoryView is read-only; mutate AgentState.context directly.");
    }
}
