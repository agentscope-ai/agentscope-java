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

package io.agentscope.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.WaitCondition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelReplyProtocolTest {
    @Test
    void takeoverBeforeWaitCommitUsesCurrentGeneration() {
        Signal old = begin("old", 1);
        Signal replacement = begin("new", 2);
        var staleWait = new WaitCondition("x.model:call:0:old", 123);
        assertEquals(
                new WaitCondition("x.model:call:0:new", 123),
                ModelReplyProtocol.waiting(List.of(old, replacement), staleWait));
        assertEquals(
                new WaitCondition("x.model:call:0:new", 123),
                ModelReplyProtocol.waiting(
                        List.of(replacement, old), new WaitCondition("x.model:call:0", 123)));
    }

    @Test
    void unrelatedWaitIsPreserved() {
        var wait = new WaitCondition("interaction:approval", 0);
        assertEquals(wait, ModelReplyProtocol.waiting(List.of(begin("new", 2)), wait));
        var applicationWait = new WaitCondition("x.app", 0);
        var applicationSignal =
                new Signal("app", "x.app", DurableJson.value(Map.of("modelOperation", "begin")));
        assertEquals(
                applicationWait,
                ModelReplyProtocol.waiting(List.of(applicationSignal), applicationWait));
    }

    private static Signal begin(String attempt, long epoch) {
        return new Signal(
                attempt,
                "x.model:call:0",
                DurableJson.value(
                        Map.of(
                                "modelOperation",
                                "begin",
                                "modelCallId",
                                "call",
                                "workerAttempt",
                                attempt,
                                "workerEpoch",
                                epoch)));
    }
}
