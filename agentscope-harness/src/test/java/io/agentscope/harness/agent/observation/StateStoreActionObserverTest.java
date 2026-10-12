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
package io.agentscope.harness.agent.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.observation.ActionObservation;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoreActionObserverTest {
    @TempDir Path directory;

    private ActionObservation start() {
        return new ActionObservation(
                "action",
                "call",
                "shell",
                "agent",
                "user",
                "session",
                10L,
                null,
                ActionObservation.Status.STARTED,
                null,
                null,
                null);
    }

    @Test
    void reloadsTypedResultWithoutConversationAndIsolatesSessions() {
        var observer = new StateStoreActionObserver(new JsonFileAgentStateStore(directory));
        var details =
                new ToolExecutionDetails("shell", ToolExecutionDetails.Outcome.SUCCEEDED, 0, false);
        var start = start();
        var settled = start.settled(ActionObservation.Status.RETURNED, null, details);
        observer.record(start, null).block();
        observer.record(settled, ToolResultBlock.text("raw output").withExecutionDetails(details))
                .block();
        var reloaded = new StateStoreActionObserver(new JsonFileAgentStateStore(directory));
        var value = reloaded.load("user", "session", "action", false).orElseThrow();
        assertEquals(settled, value.observation());
        assertEquals(details, value.result().getExecutionDetails());
        assertTrue(reloaded.load("user", "session", "action", true).isPresent());
        assertFalse(reloaded.load("user", "different", "action", false).isPresent());
        assertFalse(reloaded.load("different", "session", "action", false).isPresent());
    }

    @Test
    void identicalWriteIsIdempotentButConflictingSettlementFails() {
        var observer = new StateStoreActionObserver(new JsonFileAgentStateStore(directory));
        var settled = start().settled(ActionObservation.Status.RETURNED, null, null);
        observer.record(settled, ToolResultBlock.text("one")).block();
        observer.record(settled, ToolResultBlock.text("one")).block();
        assertThrows(
                IllegalStateException.class,
                () -> observer.record(settled, ToolResultBlock.text("two")).block());
    }

    @Test
    void startWithoutSettlementRemainsUnresolved() {
        var observer = new StateStoreActionObserver(new JsonFileAgentStateStore(directory));
        observer.record(start(), null).block();
        assertTrue(observer.load("user", "session", "action", true).isPresent());
        assertFalse(observer.load("user", "session", "action", false).isPresent());
    }
}
