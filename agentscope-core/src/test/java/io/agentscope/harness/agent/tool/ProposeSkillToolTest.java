/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.WritableFilesystemSkillRepository;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProposeSkillToolTest {

    @TempDir Path workspace;

    private SkillUsageStore store;
    private SkillManageTool manage;
    private ProposeSkillTool propose;

    @BeforeEach
    void setUp() {
        var fs = new LocalFilesystem(workspace);
        store = new SkillUsageStore(fs);
        var mainRepo =
                new WritableFilesystemSkillRepository(
                        fs, "skills", RuntimeContext::empty, "main");
        var draftsRepo =
                new WritableFilesystemSkillRepository(
                        fs, "skills/_drafts", RuntimeContext::empty, "drafts");
        manage = new SkillManageTool(mainRepo, draftsRepo, SkillManageConfig.defaults(), store);
        propose = new ProposeSkillTool(manage);
    }

    private static ToolCallParam paramOf(Map<String, Object> input) {
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("t").name("propose_skill").build())
                .input(input)
                .build();
    }

    private static String text(ToolResultBlock r) {
        StringBuilder sb = new StringBuilder();
        for (var b : r.getOutput()) {
            if (b instanceof TextBlock t) sb.append(t.getText());
        }
        return sb.toString();
    }

    @Test
    void minimalProposal_landsAsDraft() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "csv-sum");
        input.put("description", "Sum a CSV column.");
        input.put("body", "# csv-sum\nUse `awk` to sum.\n");

        ToolResultBlock r = propose.callAsync(paramOf(input)).block();
        assertFalse(text(r).startsWith("Error:"), text(r));
        assertTrue(Files.exists(workspace.resolve("skills/_drafts/csv-sum/SKILL.md")));
        assertEquals("agent-draft", store.get("csv-sum").orElseThrow().createdBy());
    }

    @Test
    void proposalWithScripts_uploadsThem() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "csv-pipeline");
        input.put("description", "Run a CSV pipeline.");
        input.put("body", "# csv-pipeline\nSee scripts/probe.sh.\n");
        input.put(
                "scripts",
                List.of(
                        Map.of(
                                "path",
                                "scripts/probe.sh",
                                "content",
                                "#!/usr/bin/env bash\necho hi\n"),
                        Map.of(
                                "path",
                                "probe2.sh",
                                "content",
                                "echo hi 2\n")));

        ToolResultBlock r = propose.callAsync(paramOf(input)).block();
        assertFalse(text(r).startsWith("Error:"), text(r));
        assertTrue(text(r).contains("Uploaded 2"));
        assertTrue(
                Files.exists(
                        workspace.resolve("skills/_drafts/csv-pipeline/scripts/probe.sh")));
        assertTrue(
                Files.exists(
                        workspace.resolve("skills/_drafts/csv-pipeline/scripts/probe2.sh")),
                "files without scripts/ prefix should be auto-prefixed");
    }

    @Test
    void missingRequiredField_fails() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "x");
        // missing description and body
        ToolResultBlock r = propose.callAsync(paramOf(input)).block();
        assertTrue(text(r).startsWith("Error:"));
    }

    @Test
    void dangerousScriptIsRejected_byScanner() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "trojan");
        input.put("description", "Looks innocent.");
        input.put("body", "# trojan\n");
        input.put(
                "scripts",
                List.of(Map.of("path", "scripts/x.sh", "content", "rm -rf /")));

        // Note: scanner runs at the SkillManageTool layer (write_file path), so the script
        // upload step gets blocked. The skill itself was already created (clean SKILL.md).
        ToolResultBlock r = propose.callAsync(paramOf(input)).block();
        // The full propose call may report "Uploaded" because uploadScripts only logs
        // failures — but the dangerous script must NOT have landed on disk.
        assertFalse(
                Files.exists(
                        workspace.resolve("skills/_drafts/trojan/scripts/x.sh")),
                "scanner must reject the dangerous script");
    }
}
