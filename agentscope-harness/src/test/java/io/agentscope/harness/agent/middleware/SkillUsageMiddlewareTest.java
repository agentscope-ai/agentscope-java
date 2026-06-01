/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class SkillUsageMiddlewareTest {

    @TempDir Path workspace;
    private SkillUsageStore store;
    private SkillUsageMiddleware mw;

    @BeforeEach
    void setUp() {
        store = new SkillUsageStore(new LocalFilesystem(workspace));
        mw = new SkillUsageMiddleware(store);
    }

    private static ToolUseBlock callOf(String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id("tu-1").name(name).input(input).build();
    }

    @Test
    void loadSkillThroughPath_bumpsViewForAgentTrackedSkill() {
        store.markAgentDraft("csv-sum", null);
        ActingInput in =
                new ActingInput(
                        List.of(callOf("load_skill_through_path", Map.of("skillId", "csv-sum"))));

        mw.onActing(null, in, x -> Flux.empty()).blockLast();

        assertEquals(1, store.get("csv-sum").orElseThrow().viewCount());
    }

    @Test
    void loadSkillThroughPath_skipsUntrackedSkill() {
        ActingInput in =
                new ActingInput(
                        List.of(
                                callOf(
                                        "load_skill_through_path",
                                        Map.of("skillId", "external-skill"))));

        mw.onActing(null, in, x -> Flux.empty()).blockLast();

        // No record was created (provenance gate refuses to track unknown skills).
        assertTrue(store.get("external-skill").isEmpty());
    }

    @Test
    void unknownTool_isIgnored() {
        store.markAgentDraft("csv-sum", null);
        ActingInput in = new ActingInput(List.of(callOf("read_file", Map.of("path", "/tmp/x"))));

        mw.onActing(null, in, x -> Flux.empty()).blockLast();
        assertEquals(0, store.get("csv-sum").orElseThrow().viewCount());
    }

    @Test
    void multipleCallsInOneActing_allBump() {
        store.markAgentDraft("a", null);
        store.markAgentDraft("b", null);
        ActingInput in =
                new ActingInput(
                        List.of(
                                callOf("load_skill_through_path", Map.of("skillId", "a")),
                                callOf("load_skill_through_path", Map.of("skillId", "b")),
                                callOf("read_file", Map.of("path", "/tmp/x"))));

        mw.onActing(null, in, x -> Flux.empty()).blockLast();
        assertEquals(1, store.get("a").orElseThrow().viewCount());
        assertEquals(1, store.get("b").orElseThrow().viewCount());
    }

    @Test
    void useSkill_bumpsUseCounter() {
        store.markAgentDraft("workflow", null);
        ActingInput in = new ActingInput(List.of(callOf("use_skill", Map.of("name", "workflow"))));

        mw.onActing(null, in, x -> Flux.empty()).blockLast();
        assertEquals(1, store.get("workflow").orElseThrow().useCount());
        assertEquals(0, store.get("workflow").orElseThrow().viewCount());
    }

    @Test
    void missingSkillIdParam_isToleratedSilently() {
        ActingInput in =
                new ActingInput(List.of(callOf("load_skill_through_path", Map.of("foo", "bar"))));
        mw.onActing(null, in, x -> Flux.empty()).blockLast();
        // No exception, no records created.
        assertTrue(store.load().isEmpty());
    }
}
