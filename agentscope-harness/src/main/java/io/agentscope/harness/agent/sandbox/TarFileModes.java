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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads, applies and converts the POSIX permission bits carried by materialized workspace
 * files.
 *
 * <p>Projected workspace files travel as a tar archive that the backend extracts with
 * {@code tar -xf}, so the entry mode is what the file ends up with inside the sandbox. A
 * script that is executable on the host has to stay executable there. The archive writer,
 * the entries materialized straight from the specs and the reference extractor all share
 * this mapping, so every path that puts a host file into a workspace agrees on one rule:
 * inherit the source's nine permission bits, and fall back to {@link #DEFAULT_FILE_MODE}
 * where the host filesystem cannot express them.
 */
final class TarFileModes {

    private static final Logger log = LoggerFactory.getLogger(TarFileModes.class);

    /** Tar mode used when the source filesystem cannot express POSIX permissions. */
    static final int DEFAULT_FILE_MODE = 0644;

    private TarFileModes() {}

    /**
     * Mode a materialized file inherits from its host source.
     *
     * <p>Filesystems without POSIX permissions (the Windows host) cannot answer, and a source
     * that vanished between the read and the stat has already contributed its bytes: both fall
     * back to {@link #DEFAULT_FILE_MODE} instead of failing the whole materialization.
     *
     * @param source host file the destination is created from
     * @return the source's nine permission bits, or {@link #DEFAULT_FILE_MODE}
     */
    static int modeOf(Path source) {
        try {
            return toMode(Files.getPosixFilePermissions(source));
        } catch (UnsupportedOperationException e) {
            // The host filesystem cannot express POSIX permissions: the default is the only
            // mode it has.
            return DEFAULT_FILE_MODE;
        } catch (IOException e) {
            log.debug("Failed to read the mode of {}: {}", source, e.getMessage());
            return DEFAULT_FILE_MODE;
        }
    }

    /**
     * Applies a materialized mode to a destination file, the way {@code tar -xf} does.
     *
     * <p>Mode {@code 0} means "not recorded" and leaves the destination untouched: a bare
     * {@code Files.copy} or an archive written without modes must not make the file
     * unreadable. Non-POSIX destinations ignore the call.
     *
     * @param target file or directory that was just created
     * @param mode nine permission bits, or {@code 0} when the source recorded none
     * @throws IOException if the destination exists but its permissions cannot be updated
     */
    static void applyTo(Path target, int mode) throws IOException {
        if ((mode & 0777) == 0) {
            return;
        }
        try {
            Files.setPosixFilePermissions(target, fromMode(mode));
        } catch (UnsupportedOperationException e) {
            // POSIX permissions unavailable (Windows default FS); intentional no-op.
        }
    }

    /** Packs POSIX permissions into the nine low bits of a tar mode. */
    static int toMode(Set<PosixFilePermission> permissions) {
        int mode = 0;
        for (PosixFilePermission permission : permissions) {
            mode |=
                    switch (permission) {
                        case OWNER_READ -> 0400;
                        case OWNER_WRITE -> 0200;
                        case OWNER_EXECUTE -> 0100;
                        case GROUP_READ -> 0040;
                        case GROUP_WRITE -> 0020;
                        case GROUP_EXECUTE -> 0010;
                        case OTHERS_READ -> 0004;
                        case OTHERS_WRITE -> 0002;
                        case OTHERS_EXECUTE -> 0001;
                    };
        }
        return mode;
    }

    /**
     * Expands the permission bits of a tar mode. Setuid, setgid and sticky bits are dropped:
     * a projected workspace file has no reason to carry them into the sandbox.
     */
    static Set<PosixFilePermission> fromMode(int mode) {
        return PosixFilePermissions.fromString(symbols(mode & 0777));
    }

    private static String symbols(int mode) {
        return new StringBuilder(9)
                .append((mode & 0400) != 0 ? 'r' : '-')
                .append((mode & 0200) != 0 ? 'w' : '-')
                .append((mode & 0100) != 0 ? 'x' : '-')
                .append((mode & 0040) != 0 ? 'r' : '-')
                .append((mode & 0020) != 0 ? 'w' : '-')
                .append((mode & 0010) != 0 ? 'x' : '-')
                .append((mode & 0004) != 0 ? 'r' : '-')
                .append((mode & 0002) != 0 ? 'w' : '-')
                .append((mode & 0001) != 0 ? 'x' : '-')
                .toString();
    }
}
