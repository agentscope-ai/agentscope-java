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

import io.agentscope.core.message.Msg;
import io.agentscope.core.state.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persisted snapshot for auto context state. */
public class AutoContextState implements State {

    private List<Msg> workingMessages = new ArrayList<>();
    private List<Msg> originalMessages = new ArrayList<>();
    private Map<String, List<Msg>> offloadContext = new HashMap<>();
    private List<CompressionEvent> compressionEvents = new ArrayList<>();

    public List<Msg> getWorkingMessages() {
        return workingMessages;
    }

    public void setWorkingMessages(List<Msg> workingMessages) {
        this.workingMessages =
                workingMessages == null ? new ArrayList<>() : new ArrayList<>(workingMessages);
    }

    public List<Msg> getOriginalMessages() {
        return originalMessages;
    }

    public void setOriginalMessages(List<Msg> originalMessages) {
        this.originalMessages =
                originalMessages == null ? new ArrayList<>() : new ArrayList<>(originalMessages);
    }

    public Map<String, List<Msg>> getOffloadContext() {
        return offloadContext;
    }

    public void setOffloadContext(Map<String, List<Msg>> offloadContext) {
        this.offloadContext =
                offloadContext == null ? new HashMap<>() : new HashMap<>(offloadContext);
    }

    public List<CompressionEvent> getCompressionEvents() {
        return compressionEvents;
    }

    public void setCompressionEvents(List<CompressionEvent> compressionEvents) {
        this.compressionEvents =
                compressionEvents == null ? new ArrayList<>() : new ArrayList<>(compressionEvents);
    }
}
