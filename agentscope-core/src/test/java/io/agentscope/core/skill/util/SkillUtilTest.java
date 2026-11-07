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
package io.agentscope.core.skill.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden and behavior tests for {@link SkillUtil}'s directory-versioning helpers.
 *
 * <p>The same directory bytes must forever hash to the same value, so the golden value below
 * pins the framing byte-for-byte: any change to field order, separators or encoding breaks
 * this suite on purpose. The remaining tests cover determinism, change sensitivity,
 * hidden-entry handling, and the {@link SkillUtil#statFingerprint(Path)} format.
 *
 * <p>Symlinks are not exercised: creating them needs privileges on Windows, and the
 * no-follow rule is stated in the {@code hashSkillDirectory} javadoc.
 */
@Tag("unit")
@DisplayName("SkillUtil directory versioning")
class SkillUtilTest {

    /**
     * Golden value for {@link SkillUtil#hashSkillDirectory(Path)} over a directory holding
     * SKILL.md ("# Demo\n") and resources/note.txt ("note") — pinned regardless of the
     * directory's own location.
     */
    private static final String GOLDEN_OF_DIRECTORY =
            "8992c183809e3ae6b2d03db156273e18d96c42f87247c3611bc11121337ca9b1";

    @Test
    @DisplayName("hashSkillDirectory: golden value pins the on-disk framing forever")
    void ofDirectoryGoldenValue(@TempDir Path dir) throws IOException {
        writeDemoTree(dir);
        assertEquals(GOLDEN_OF_DIRECTORY, SkillUtil.hashSkillDirectory(dir));
    }

    @Test
    @DisplayName("hashSkillDirectory: the hash depends on the tree, never on its location")
    void ofDirectoryIsRootIndependent(@TempDir Path rootA, @TempDir Path rootB) throws IOException {
        writeDemoTree(rootA.resolve("skill"));
        writeDemoTree(rootB.resolve("elsewhere").resolve("skill"));
        assertEquals(
                SkillUtil.hashSkillDirectory(rootA.resolve("skill")),
                SkillUtil.hashSkillDirectory(rootB.resolve("elsewhere").resolve("skill")));
    }

    @Test
    @DisplayName("hashSkillDirectory: hidden files are skipped and hidden directories pruned whole")
    void ofDirectoryIgnoresHiddenEntries(@TempDir Path clean, @TempDir Path noisy)
            throws IOException {
        writeDemoTree(clean);
        writeDemoTree(noisy);
        Files.writeString(noisy.resolve(".hidden"), "secret");
        Files.createDirectories(noisy.resolve(".git"));
        Files.writeString(noisy.resolve(".git").resolve("config"), "[core]");
        assertEquals(
                SkillUtil.hashSkillDirectory(clean),
                SkillUtil.hashSkillDirectory(noisy),
                "a .git subtree or hidden file must not change the version");
    }

    @Test
    @DisplayName("hashSkillDirectory: any participating byte change mints a new hash")
    void ofDirectoryIsChangeSensitive(@TempDir Path dir) throws IOException {
        writeDemoTree(dir);
        String before = SkillUtil.hashSkillDirectory(dir);
        Files.writeString(dir.resolve("resources").resolve("note.txt"), "note!");
        assertNotEquals(before, SkillUtil.hashSkillDirectory(dir));
    }

    @Test
    @DisplayName("statFingerprint: path|size|mtime per hashed file, ; -terminated")
    void statFingerprintFormat(@TempDir Path dir) throws IOException {
        writeDemoTree(dir);
        Files.setLastModifiedTime(dir.resolve("SKILL.md"), FileTime.fromMillis(1000));
        Files.setLastModifiedTime(
                dir.resolve("resources").resolve("note.txt"), FileTime.fromMillis(2000));
        assertEquals("SKILL.md|7|1000;resources/note.txt|4|2000;", SkillUtil.statFingerprint(dir));
    }

    @Test
    @DisplayName("statFingerprint: covers exactly the hashed file set (hidden entries absent)")
    void statFingerprintMatchesHashedFileSet(@TempDir Path dir) throws IOException {
        writeDemoTree(dir);
        Files.writeString(dir.resolve(".hidden"), "secret");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git").resolve("config"), "[core]");
        Files.setLastModifiedTime(dir.resolve("SKILL.md"), FileTime.fromMillis(1000));
        Files.setLastModifiedTime(
                dir.resolve("resources").resolve("note.txt"), FileTime.fromMillis(2000));
        assertEquals(
                "SKILL.md|7|1000;resources/note.txt|4|2000;",
                SkillUtil.statFingerprint(dir),
                "hidden files and hidden subtrees must be absent from the fingerprint");
    }

    @Test
    @DisplayName("statFingerprint: a content rewrite (size/mtime move) changes the fingerprint")
    void statFingerprintIsChangeSensitive(@TempDir Path dir) throws IOException {
        writeDemoTree(dir);
        Files.setLastModifiedTime(dir.resolve("SKILL.md"), FileTime.fromMillis(1000));
        Files.setLastModifiedTime(
                dir.resolve("resources").resolve("note.txt"), FileTime.fromMillis(2000));
        String before = SkillUtil.statFingerprint(dir);
        Files.writeString(dir.resolve("SKILL.md"), "# Demos\n");
        assertNotEquals(before, SkillUtil.statFingerprint(dir));
    }

    /** SKILL.md ("# Demo\n") + resources/note.txt ("note") — the golden tree. */
    private static void writeDemoTree(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("resources"));
        Files.writeString(dir.resolve("SKILL.md"), "# Demo\n");
        Files.writeString(dir.resolve("resources").resolve("note.txt"), "note");
    }
}
