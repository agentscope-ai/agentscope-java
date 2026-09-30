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
package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class MarketplaceStagerLazyTest {
    @TempDir Path temp;

    private Path source() throws Exception {
        Path source = Files.createDirectories(temp.resolve("library/demo"));
        Files.writeString(
                source.resolve("SKILL.md"), "---\nname: demo\ndescription: demo\n---\nbody\n");
        return source;
    }

    private FileSystemSkillRepository repository(boolean lazy) {
        return new FileSystemSkillRepository(temp.resolve("library"), false, "fixture", lazy);
    }

    private Path staged() {
        return temp.resolve("workspace/.skills-cache/session/fixture/demo");
    }

    private StageResult stage(FileSystemSkillRepository repo, AgentSkill skill) {
        return new MarketplaceStager(temp.resolve("workspace"))
                .stage(List.of(new RepoBound(skill, repo)), Map.of(repo, "fixture"), "session")
                .get("demo");
    }

    @Test
    void lazyRepositoryStagesScriptsAndBinaryResourcesWithoutLoadingTheMap() throws Exception {
        Path source = source();
        Files.createDirectories(source.resolve("scripts"));
        Files.writeString(source.resolve("scripts/run.py"), "print('hi')\n");
        byte[] binary = {0, (byte) 0xff, 42};
        Files.write(source.resolve("data.bin"), binary);
        Files.writeString(source.resolve(".secret"), "hidden");
        Files.createDirectories(source.resolve(".private"));
        Files.writeString(source.resolve(".private/secret.txt"), "hidden");
        FileSystemSkillRepository repo = repository(true);
        AgentSkill skill = repo.getAllSkills().get(0);

        assertTrue(skill.getResources().isEmpty());
        assertInstanceOf(StageResult.Cached.class, stage(repo, skill));
        assertEquals("print('hi')\n", Files.readString(staged().resolve("scripts/run.py")));
        assertArrayEquals(binary, Files.readAllBytes(staged().resolve("data.bin")));
        assertFalse(Files.exists(staged().resolve("SKILL.md")));
        assertFalse(Files.exists(staged().resolve(".secret")));
        assertFalse(Files.exists(staged().resolve(".private")));
        assertTrue(skill.getResources().isEmpty());
    }

    @Test
    void restagingRefreshesChangedFilesAndRemovesDeletedFiles() throws Exception {
        Path source = source();
        Path script = source.resolve("run.py");
        Files.writeString(script, "first");
        Files.writeString(source.resolve("removed.txt"), "old");
        FileSystemSkillRepository repo = repository(true);
        AgentSkill skill = repo.getAllSkills().get(0);
        stage(repo, skill);
        Files.setLastModifiedTime(staged().resolve("run.py"), FileTime.fromMillis(1000));
        stage(repo, skill);
        assertEquals(
                FileTime.fromMillis(1000), Files.getLastModifiedTime(staged().resolve("run.py")));

        Files.writeString(script, "second");
        Files.delete(source.resolve("removed.txt"));
        stage(repo, skill);
        assertEquals("second", Files.readString(staged().resolve("run.py")));
        assertFalse(Files.exists(staged().resolve("removed.txt")));

        Files.delete(script);
        stage(repo, skill);
        assertFalse(Files.exists(staged().resolve("run.py")));
    }

    @Test
    void eagerResourcesRemainAuthoritativeEvenWhenOriginChanges() throws Exception {
        Path source = source();
        Files.writeString(source.resolve("run.py"), "loaded");
        FileSystemSkillRepository repo = repository(false);
        AgentSkill skill = repo.getAllSkills().get(0);
        Files.writeString(source.resolve("run.py"), "disk changed");
        assertInstanceOf(StageResult.Cached.class, stage(repo, skill));
        assertEquals("loaded", Files.readString(staged().resolve("run.py")));
    }

    @Test
    void missingOriginIsNotReportedAsCached() throws Exception {
        Path source = source();
        FileSystemSkillRepository repo = repository(true);
        AgentSkill skill = repo.getAllSkills().get(0);
        Files.delete(source.resolve("SKILL.md"));
        Files.delete(source);
        assertEquals(StageResult.NONE, stage(repo, skill));
    }

    @Test
    void emptySkillWithoutOriginKeepsExistingStagingBehavior() throws Exception {
        source();
        AgentSkill skill = new AgentSkill("demo", "demo", "body", null);
        assertInstanceOf(StageResult.Cached.class, stage(repository(true), skill));
    }

    @Test
    void cacheInsideSourceIsRejectedBeforeCreatingDirectories() throws Exception {
        Path source = source();
        FileSystemSkillRepository repo = repository(true);
        AgentSkill skill = repo.getAllSkills().get(0);
        Path workspace = source.resolve("workspace");
        StageResult result =
                new MarketplaceStager(workspace)
                        .stage(
                                List.of(new RepoBound(skill, repo)),
                                Map.of(repo, "fixture"),
                                "session")
                        .get("demo");
        assertEquals(StageResult.NONE, result);
        assertFalse(Files.exists(workspace));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void sourceSymlinksAreNotCopiedAndScriptsAreExecutable() throws Exception {
        Path source = source();
        Files.writeString(source.resolve("run.py"), "print('hi')");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(source.resolve("linked-dir"), outside);
        Files.createSymbolicLink(source.resolve("linked-file"), outside.resolve("secret.txt"));
        FileSystemSkillRepository repo = repository(true);
        assertInstanceOf(StageResult.Cached.class, stage(repo, repo.getAllSkills().get(0)));
        assertTrue(Files.isExecutable(staged().resolve("run.py")));
        assertFalse(Files.exists(staged().resolve("linked-dir")));
        assertFalse(Files.exists(staged().resolve("linked-file")));
    }

    @Test
    void sourceInsideCacheIsRejectedWithoutRemovingSourceFiles() throws Exception {
        Path source = Files.createDirectories(staged().resolve("source"));
        Files.writeString(
                source.resolve("SKILL.md"), "---\nname: demo\ndescription: demo\n---\nbody\n");
        Files.writeString(source.resolve("run.py"), "original");
        FileSystemSkillRepository repo =
                new FileSystemSkillRepository(staged(), false, "fixture", true);
        assertEquals(StageResult.NONE, stage(repo, repo.getAllSkills().get(0)));
        assertEquals("original", Files.readString(source.resolve("run.py")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void destinationSymlinkDoesNotWriteOutsideTheCache() throws Exception {
        Path source = source();
        Files.createDirectories(source.resolve("scripts"));
        Files.writeString(source.resolve("scripts/run.py"), "print('hi')");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.createDirectories(staged());
        Files.createSymbolicLink(staged().resolve("scripts"), outside);
        FileSystemSkillRepository repo = repository(true);
        assertEquals(StageResult.NONE, stage(repo, repo.getAllSkills().get(0)));
        assertFalse(Files.exists(outside.resolve("run.py")));
    }
}
