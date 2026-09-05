/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.aistio.proto.SessionEventMsg;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventJournalTest {

    @TempDir Path tempDir;

    @Test
    void survivesRestartAndRemovesOnlyAcknowledgedWatermarks() throws Exception {
        AistioConfig config =
                AistioConfig.builder("agent")
                        .instanceKey("instance")
                        .enableEvents(true)
                        .eventJournalDir(tempDir.toString())
                        .startHttp(false)
                        .startGrpc(false)
                        .build();
        EventJournal journal = new EventJournal(config);
        journal.append(event("s1", 1, "first"));
        journal.append(event("s2", 1, "other"));
        journal.append(event("s1", 2, "second"));

        EventJournal reopened = new EventJournal(config);
        assertEquals(Map.of("s1", 2, "s2", 1), reopened.latestSequences());
        assertEquals(3, reopened.first(20).size());

        reopened.acknowledge(Map.of("s1", 1));
        EventJournal afterAck = new EventJournal(config);
        assertEquals(2, afterAck.first(20).size());
        assertEquals("other", afterAck.first(20).get(0).getContent());
        assertEquals("second", afterAck.first(20).get(1).getContent());

        afterAck.acknowledge(Map.of("s1", 2, "s2", 1));
        EventJournal emptyButSequenced = new EventJournal(config);
        assertEquals(0, emptyButSequenced.first(20).size());
        assertEquals(Map.of("s1", 2, "s2", 1), emptyButSequenced.latestSequences());
    }

    @Test
    void keepsCompleteMessagePayload() throws Exception {
        AistioConfig config =
                AistioConfig.builder("agent")
                        .instanceKey("complete")
                        .enableEvents(true)
                        .eventJournalDir(tempDir.toString())
                        .startHttp(false)
                        .startGrpc(false)
                        .build();
        String content = "x".repeat(20_000);
        EventJournal journal = new EventJournal(config);
        journal.append(event("s1", 1, content));

        assertEquals(content, new EventJournal(config).first(1).get(0).getContent());
    }

    private static SessionEventMsg event(String sessionId, int seq, String content) {
        return SessionEventMsg.newBuilder()
                .setSessionId(sessionId)
                .setSeq(seq)
                .setEventType("message")
                .setContent(content)
                .build();
    }
}
