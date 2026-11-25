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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TarFileModesTest {

    @TempDir Path tempDir;

    @Test
    void toMode_packsTheNinePermissionBits() {
        assertEquals(0755, TarFileModes.toMode(PosixFilePermissions.fromString("rwxr-xr-x")));
        assertEquals(0644, TarFileModes.toMode(PosixFilePermissions.fromString("rw-r--r--")));
        assertEquals(0600, TarFileModes.toMode(PosixFilePermissions.fromString("rw-------")));
        // Asymmetric triplets: with 0755/0644 alone, swapping the group and other bits would
        // still pass.
        assertEquals(0640, TarFileModes.toMode(PosixFilePermissions.fromString("rw-r-----")));
        assertEquals(0604, TarFileModes.toMode(PosixFilePermissions.fromString("rw----r--")));
        assertEquals(0000, TarFileModes.toMode(Set.of()));
    }

    @Test
    void fromMode_expandsTheNinePermissionBits() {
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), TarFileModes.fromMode(0755));
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), TarFileModes.fromMode(0644));
        assertEquals(PosixFilePermissions.fromString("rw-r-----"), TarFileModes.fromMode(0640));
        assertEquals(PosixFilePermissions.fromString("rw----r--"), TarFileModes.fromMode(0604));
    }

    @Test
    void fromMode_dropsSetuidSetgidAndStickyBits() {
        // 04755 is a setuid executable, 01777 a world-writable sticky directory. Neither may
        // travel from a projected host file into the sandbox.
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), TarFileModes.fromMode(04755));
        assertEquals(PosixFilePermissions.fromString("rwxrwxrwx"), TarFileModes.fromMode(01777));
    }

    @Test
    void roundTrip_keepsThePermissionTriplets() {
        for (String symbols :
                new String[] {
                    "rwxrwxrwx", "rw-r--r--", "rw-r-----", "rw----r--", "r--------", "---------"
                }) {
            assertEquals(
                    PosixFilePermissions.fromString(symbols),
                    TarFileModes.fromMode(
                            TarFileModes.toMode(PosixFilePermissions.fromString(symbols))));
        }
    }

    @Test
    void modeOf_readsTheSourcePermissions() throws Exception {
        Path source = tempDir.resolve("run.sh");
        Files.writeString(source, "#!/bin/sh\n");
        assumeTrue(supportsPosix(source), "POSIX permissions are required for this assertion");
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rwxr-x---"));

        assertEquals(0750, TarFileModes.modeOf(source));
    }

    @Test
    void modeOf_fallsBackWhenTheSourceIsGone() {
        // The source vanished between the read and the stat: its bytes are already on their
        // way, so the materialization keeps the default mode instead of failing.
        assertEquals(
                TarFileModes.DEFAULT_FILE_MODE,
                TarFileModes.modeOf(tempDir.resolve("does-not-exist")));
    }

    @Test
    void applyTo_leavesTheTargetAloneWhenNoModeWasRecorded() throws Exception {
        Path target = tempDir.resolve("plain.txt");
        Files.writeString(target, "x");
        assumeTrue(supportsPosix(target), "POSIX permissions are required for this assertion");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        // Mode 0 reads as "not recorded"; applying it would make the file unreadable.
        TarFileModes.applyTo(target, 0);

        assertEquals(
                PosixFilePermissions.fromString("rw-r--r--"),
                Files.getPosixFilePermissions(target));
    }

    @Test
    void applyTo_setsTheRecordedMode() throws Exception {
        Path target = tempDir.resolve("run.sh");
        Files.writeString(target, "#!/bin/sh\n");
        assumeTrue(supportsPosix(target), "POSIX permissions are required for this assertion");

        TarFileModes.applyTo(target, 0755);

        assertEquals(
                PosixFilePermissions.fromString("rwxr-xr-x"),
                Files.getPosixFilePermissions(target));
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
