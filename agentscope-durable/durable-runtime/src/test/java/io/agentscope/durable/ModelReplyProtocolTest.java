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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void onlyCurrentGenerationCanReplyAndSeal() {
        var signals = List.of(begin("old", 1), begin("current", 2));
        assertFalse(ModelReplyProtocol.accepts(signals, operation("old", "reply")));
        var error =
                assertThrows(
                        DurableException.class,
                        () -> ModelReplyProtocol.accepts(signals, operation("old", "seal")));
        assertEquals(DurableException.Code.UNAVAILABLE, error.code());
        assertTrue(ModelReplyProtocol.accepts(signals, operation("current", "reply")));
        assertTrue(ModelReplyProtocol.accepts(signals, operation("current", "seal")));
        assertTrue(ModelReplyProtocol.accepts(signals, operation("replacement", "begin")));
    }

    @Test
    void sealedGenerationRejectsRepliesAndTakeovers() {
        var signals = List.of(begin("winner", 1), operation("winner", "seal"));
        assertTrue(ModelReplyProtocol.sealed(signals, "call"));
        assertFalse(ModelReplyProtocol.sealed(signals, "another-call"));
        assertFalse(ModelReplyProtocol.accepts(signals, operation("winner", "reply")));
        assertFalse(ModelReplyProtocol.accepts(signals, operation("new", "begin")));
    }

    @Test
    void foreignSignalsCannotChangeModelGeneration() {
        var foreign = new Signal("foreign", "x.app", operation("foreign", "begin").value());
        var otherCall =
                new Signal(
                        "other",
                        "x.model:other:0",
                        DurableJson.value(
                                Map.of(
                                        "modelOperation",
                                        "begin",
                                        "modelCallId",
                                        "other",
                                        "workerAttempt",
                                        "other",
                                        "workerEpoch",
                                        999)));
        assertEquals("0", ModelReplyProtocol.active(List.of(foreign, otherCall), "call"));
        assertTrue(ModelReplyProtocol.accepts(List.of(), foreign));
        assertTrue(
                ModelReplyProtocol.accepts(
                        List.of(),
                        new Signal(
                                "raw", "x.model:call:0", DurableJson.value(Map.of("last", true)))));
        assertNull(ModelReplyProtocol.waiting(List.of(), null));
        assertEquals(
                new WaitCondition("x.model:call:0", 10),
                ModelReplyProtocol.waiting(
                        List.of(begin("0", 1)), new WaitCondition("x.model:call:0", 10)));
    }

    @Test
    void invalidFencingIdentityIsRejected() {
        var blank =
                new Signal(
                        "bad",
                        "x.model:call:0",
                        DurableJson.value(
                                Map.of(
                                        "modelOperation",
                                        "begin",
                                        "modelCallId",
                                        "call",
                                        "workerAttempt",
                                        " ")));
        assertEquals(
                DurableException.Code.INVALID,
                assertThrows(
                                DurableException.class,
                                () -> ModelReplyProtocol.accepts(List.of(), blank))
                        .code());
    }

    private static Signal operation(String attempt, String operation) {
        return new Signal(
                attempt + operation,
                "x.model:call:" + operation,
                DurableJson.value(
                        Map.of(
                                "modelOperation",
                                operation,
                                "modelCallId",
                                "call",
                                "workerAttempt",
                                attempt)));
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
