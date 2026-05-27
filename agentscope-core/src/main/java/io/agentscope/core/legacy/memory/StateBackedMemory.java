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

import io.agentscope.core.legacy.session.Session;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.SessionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Memory} implementation that delegates all reads and writes to
 * {@link AgentState#contextMutable()}.
 *
 * <p>This adapter exists solely to preserve backward compatibility for code that
 * accesses conversation history via {@code agent.getMemory().getMessages()}.
 * The canonical location of conversation context in 2.0 is
 * {@link AgentState#getContext()}.
 *
 * @deprecated since 2.0.0. Use {@link AgentState#getContext()} directly.
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class StateBackedMemory implements Memory {

    private final AgentState state;

    public StateBackedMemory(AgentState state) {
        this.state = state;
    }

    @Override
    public void addMessage(Msg message) {
        state.contextMutable().add(message);
    }

    @Override
    public List<Msg> getMessages() {
        return new ArrayList<>(state.contextMutable());
    }

    @Override
    public void deleteMessage(int index) {
        List<Msg> ctx = state.contextMutable();
        if (index >= 0 && index < ctx.size()) {
            ctx.remove(index);
        }
    }

    @Override
    public void clear() {
        state.contextMutable().clear();
    }

    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        session.save(sessionKey, "memory_messages", new ArrayList<>(state.contextMutable()));
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        List<Msg> loaded = session.getList(sessionKey, "memory_messages", Msg.class);
        state.contextMutable().clear();
        state.contextMutable().addAll(loaded);
    }
}
