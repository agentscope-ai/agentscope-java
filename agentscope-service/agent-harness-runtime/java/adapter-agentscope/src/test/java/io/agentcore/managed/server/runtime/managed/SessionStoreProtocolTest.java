/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SessionStoreProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void commitEnvelopeRoundTripsWithoutKernelSdkTypes() throws Exception {
        String encoded =
                """
                {"schemaVersion":1,"scope":{"tenant":"t","namespace":"n","sessionId":"s"},
                 "turnId":"turn","epoch":2,"executionToken":"token","writerOwner":"owner",
                 "commitId":"commit","expectedSequence":4,
                 "recoveryFacts":[{"schemaVersion":1,"eventId":"event","sequence":5,
                   "occurredAt":1,"type":"fixture/fact","executionRunId":"run","turnId":"turn",
                   "required":false,"payloadJson":"{}"}],
                 "toolIntents":[],"inputReceipts":[],"terminalStatus":null,"suspension":null}
                """;
        var commit = mapper.readValue(encoded, SessionCommit.class);
        assertEquals(
                commit,
                ManagedSessionCommitAdapter.encode(ManagedSessionCommitAdapter.decode(commit)));
        assertEquals(mapper.readTree(encoded), mapper.readTree(mapper.writeValueAsBytes(commit)));
        assertThrows(
                Exception.class,
                () ->
                        mapper.readValue(
                                encoded.replace(
                                        "\"schemaVersion\":1,\"scope\"",
                                        "\"schemaVersion\":2,\"scope\""),
                                SessionCommit.class));
    }

    @Test
    void commitReceiptUsesTheExistingLanguageNeutralFields() throws Exception {
        String encoded =
                """
                {"batchId":"commit-7","hash":"hash-7","outcome":"committed",
                 "errorCode":null,"sequence":9,"epoch":3}
                """;
        var receipt = mapper.readValue(encoded, SessionCommitReceipt.class);
        assertEquals(
                new SessionCommitReceipt("commit-7", "hash-7", "committed", null, 9, 3), receipt);
        assertEquals(mapper.readTree(encoded), mapper.readTree(mapper.writeValueAsBytes(receipt)));
    }

    @Test
    void recoveryHeadKeepsEpochAndCheckpointPositionSeparate() throws Exception {
        String encoded =
                """
                {"sequence":9,"epoch":3,"token":"owner-3","commitId":"commit-7",
                 "checkpoint":null,"checkpointSequence":6}
                """;
        var head = mapper.readValue(encoded, SessionRecoveryHead.class);
        assertEquals(new SessionRecoveryHead(9, 3, "owner-3", "commit-7", null, 6), head);
        assertEquals(mapper.readTree(encoded), mapper.readTree(mapper.writeValueAsBytes(head)));
    }
}
