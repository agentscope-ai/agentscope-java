/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.agentscope.core.a2a.server.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.a2a.agent.hitl.A2aExternalToolResponse;
import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.hitl.A2aUserConfirmation;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HitlResumeMessageFactoryTest {

    @Test
    void shouldRebuildConfirmationFromServerToolAndUseModifiedInput() {
        HitlHandoffRecord record = record(A2aHandoffType.USER_CONFIRM);
        PermissionRule rule = new PermissionRule("probe", null, PermissionBehavior.ALLOW, "user");

        List<Msg> result =
                HitlResumeMessageFactory.create(
                        record,
                        List.of(
                                new A2aUserConfirmation(
                                        "call-1", true, Map.of("value", 2), List.of(rule))));

        assertEquals(1, result.size());
        @SuppressWarnings("unchecked")
        List<ConfirmResult> confirmations =
                (List<ConfirmResult>) result.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertEquals(1, confirmations.size());
        ToolUseBlock rebuilt = confirmations.get(0).getToolCall();
        assertEquals("call-1", rebuilt.getId());
        assertEquals("probe", rebuilt.getName());
        assertEquals(Map.of("value", 2), rebuilt.getInput());
        assertTrue(rebuilt.getContent().contains("\"value\":2"));
        assertEquals(List.of(rule), confirmations.get(0).getRules());
        assertTrue(
                result.get(0).getContentBlocks(ToolResultBlock.class).stream()
                        .anyMatch(
                                block ->
                                        HitlResumeMessageFactory.CONFIRMATION_RECOVERY_GUARD_ID
                                                .equals(block.getId())));
    }

    @Test
    void shouldRebuildExternalResultsWithServerOwnedNameAndId() {
        HitlHandoffRecord record = record(A2aHandoffType.EXTERNAL_EXECUTION);

        List<Msg> result =
                HitlResumeMessageFactory.create(
                        record,
                        List.of(
                                new A2aExternalToolResponse(
                                        "call-1",
                                        ToolResultState.SUCCESS,
                                        List.of(TextBlock.builder().text("done").build()),
                                        Map.of("provider", "external"))));

        ToolResultBlock block = result.get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals("call-1", block.getId());
        assertEquals("probe", block.getName());
        assertEquals(ToolResultState.SUCCESS, block.getState());
        assertEquals("done", ((TextBlock) block.getOutput().get(0)).getText());
    }

    @Test
    void shouldPreserveDeniedConfirmationAndExternalErrorData() {
        List<Msg> denied =
                HitlResumeMessageFactory.create(
                        record(A2aHandoffType.USER_CONFIRM),
                        List.of(new A2aUserConfirmation("call-1", false, null, List.of())));
        @SuppressWarnings("unchecked")
        List<ConfirmResult> confirmations =
                (List<ConfirmResult>) denied.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertFalse(confirmations.get(0).isConfirmed());

        DataBlock diagnostic =
                DataBlock.builder()
                        .id("diagnostic-1")
                        .name("failure.json")
                        .source(
                                new URLSource(
                                        "https://example.test/failure.json", "application/json"))
                        .build();
        List<Msg> external =
                HitlResumeMessageFactory.create(
                        record(A2aHandoffType.EXTERNAL_EXECUTION),
                        List.of(
                                new A2aExternalToolResponse(
                                        "call-1",
                                        ToolResultState.ERROR,
                                        List.of(diagnostic),
                                        Map.of("errorCode", "UPSTREAM"))));

        ToolResultBlock result = external.get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals(ToolResultState.ERROR, result.getState());
        DataBlock rebuiltDiagnostic = (DataBlock) result.getOutput().get(0);
        assertEquals(diagnostic.getId(), rebuiltDiagnostic.getId());
        assertEquals(diagnostic.getName(), rebuiltDiagnostic.getName());
        URLSource rebuiltSource = (URLSource) rebuiltDiagnostic.getSource();
        assertEquals("https://example.test/failure.json", rebuiltSource.getUrl());
        assertEquals("application/json", rebuiltSource.getMimeType());
        assertEquals("UPSTREAM", result.getMetadata().get("errorCode"));
    }

    @Test
    void shouldRejectMissingExtraDuplicateAndMismatchedResponses() {
        HitlHandoffRecord record = record(A2aHandoffType.USER_CONFIRM);
        A2aUserConfirmation valid = new A2aUserConfirmation("call-1", true, null, List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> HitlResumeMessageFactory.create(record, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> HitlResumeMessageFactory.create(record, List.of(valid, valid)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HitlResumeMessageFactory.create(
                                record,
                                List.of(new A2aUserConfirmation("extra", true, null, List.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HitlResumeMessageFactory.create(
                                record,
                                List.of(
                                        new A2aExternalToolResponse(
                                                "call-1",
                                                ToolResultState.SUCCESS,
                                                List.of(),
                                                Map.of()))));
    }

    private HitlHandoffRecord record(A2aHandoffType type) {
        return new HitlHandoffRecord(
                "handoff-1",
                "task-1",
                "context-1",
                new HitlExecutionKey("alice", "agent", "context-1"),
                type,
                List.of(
                        new ToolUseBlock(
                                "call-1",
                                "probe",
                                Map.of("value", 1),
                                "{\"value\":1}",
                                Map.of("provider", "test"))),
                "fingerprint",
                "digest",
                Instant.parse("2030-01-01T00:00:00Z"),
                HitlHandoffStatus.OPEN);
    }
}
