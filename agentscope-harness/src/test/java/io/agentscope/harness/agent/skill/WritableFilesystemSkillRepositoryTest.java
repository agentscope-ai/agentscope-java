/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class WritableFilesystemSkillRepositoryTest {

    @TempDir Path workspace;

    private LocalFilesystem fs;
    private WritableFilesystemSkillRepository repo;

    @BeforeEach
    void setUp() {
        fs = new LocalFilesystem(workspace);
        repo = new WritableFilesystemSkillRepository(fs, "skills", RuntimeContext::empty, "test");
    }

    @Test
    void isWriteableByDefault() {
        assertTrue(repo.isWriteable());
        repo.setWriteable(false);
        assertFalse(repo.isWriteable());
    }

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        AgentSkill skill =
                new AgentSkill(
                        "csv-sum",
                        "Sum a CSV column and write the total to a file.",
                        "# CSV Sum\n\nUse `awk` to sum column.\n",
                        null);
        assertTrue(repo.save(List.of(skill), false));

        // SKILL.md exists on disk under skills/csv-sum/
        Path skillMd = workspace.resolve("skills/csv-sum/SKILL.md");
        assertTrue(Files.isRegularFile(skillMd));
        String text = Files.readString(skillMd);
        assertTrue(text.startsWith("---"));
        assertTrue(text.contains("name: csv-sum"));
        assertTrue(text.contains("description:"));
        assertTrue(text.contains("# CSV Sum"));

        // Repository can load it back
        AgentSkill loaded = repo.getSkill("csv-sum");
        assertNotNull(loaded);
        assertEquals("csv-sum", loaded.getName());
        assertEquals("Sum a CSV column and write the total to a file.", loaded.getDescription());
    }

    @Test
    void saveRefusesDuplicateUnlessForce() {
        AgentSkill v1 =
                new AgentSkill("dup", "First version (long enough description).", "# v1", null);
        AgentSkill v2 =
                new AgentSkill("dup", "Second version (long enough description).", "# v2", null);
        assertTrue(repo.save(List.of(v1), false));
        assertFalse(repo.save(List.of(v2), false), "Duplicate save without force should fail");
        assertTrue(repo.save(List.of(v2), true), "Force save should overwrite");
        AgentSkill loaded = repo.getSkill("dup");
        assertTrue(loaded.getSkillContent().contains("# v2"));
    }

    @Test
    void saveWritesResources() {
        AgentSkill skill =
                new AgentSkill(
                        "with-script",
                        "A skill with a helper script.",
                        "# With Script\n",
                        java.util.Map.of(
                                "scripts/probe.sh", "#!/usr/bin/env bash\necho hello\n",
                                "references/api.md", "# API Reference\n"));
        assertTrue(repo.save(List.of(skill), false));

        assertTrue(Files.isRegularFile(workspace.resolve("skills/with-script/scripts/probe.sh")));
        assertTrue(Files.isRegularFile(workspace.resolve("skills/with-script/references/api.md")));
    }

    @Test
    void deleteArchivesNonDestructively() throws IOException {
        AgentSkill skill = new AgentSkill("archive-me", "To be archived.", "# Archive me\n", null);
        assertTrue(repo.save(List.of(skill), false));

        assertTrue(repo.delete("archive-me"));

        // Live skill dir is gone…
        assertFalse(Files.exists(workspace.resolve("skills/archive-me")));
        // …but its content lives under skills/.archive/archive-me-<ts>/
        Path archiveRoot = workspace.resolve("skills/.archive");
        assertTrue(Files.isDirectory(archiveRoot));
        try (Stream<Path> children = Files.list(archiveRoot)) {
            long count =
                    children.filter(p -> p.getFileName().toString().startsWith("archive-me-"))
                            .count();
            assertEquals(1, count, "Exactly one timestamped archive folder should exist");
        }
    }

    @Test
    void writeReadDeleteSkillFileRoundTrip() {
        AgentSkill skill =
                new AgentSkill("with-files", "Has support files.", "# With Files\n", null);
        assertTrue(repo.save(List.of(skill), false));

        assertTrue(repo.writeSkillFile("with-files", "scripts/run.sh", "#!/bin/sh\necho hi\n"));
        String read = repo.readSkillFile("with-files", "scripts/run.sh");
        assertNotNull(read);
        assertTrue(read.contains("echo hi"));
        assertTrue(repo.deleteSkillFile("with-files", "scripts/run.sh"));
        assertNull(repo.readSkillFile("with-files", "scripts/run.sh"));
    }

    @Test
    void getAllSkillsFiltersMetadataDirectories() throws IOException {
        // One legit skill + one in a metadata directory (_drafts/)
        AgentSkill prod = new AgentSkill("prod-skill", "Live skill.", "# Prod\n", null);
        assertTrue(repo.save(List.of(prod), false));

        // Manually plant a SKILL.md under _drafts/ to simulate a draft.
        Path draftDir = workspace.resolve("skills/_drafts/draft-skill");
        Files.createDirectories(draftDir);
        Files.writeString(
                draftDir.resolve("SKILL.md"),
                "---\nname: draft-skill\ndescription: A draft.\n---\n# Draft\n");

        List<AgentSkill> all = repo.getAllSkills();
        assertEquals(1, all.size(), "Drafts under _drafts/ must not be returned");
        assertEquals("prod-skill", all.get(0).getName());
    }
}
