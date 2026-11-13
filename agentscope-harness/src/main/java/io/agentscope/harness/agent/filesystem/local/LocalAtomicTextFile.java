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
package io.agentscope.harness.agent.filesystem.local;

import io.agentscope.harness.agent.filesystem.AtomicTextFile;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Conditional UTF-8 writes using an OS lock and atomic rename. */
public final class LocalAtomicTextFile implements AtomicTextFile {
    // Stripes bound lock memory while preventing overlapping JVM FileLocks on the same path.
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new ReentrantLock();
    }

    private final Path path;

    public LocalAtomicTextFile(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        try {
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS))
                existing = existing.getParent();
            this.path = existing.toRealPath().resolve(existing.relativize(absolute));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String read() {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean compareAndSet(String expected, String updated) {
        Objects.requireNonNull(updated, "updated");
        ReentrantLock local = LOCKS[Math.floorMod(path.hashCode(), LOCKS.length)];
        local.lock();
        try {
            Files.createDirectories(path.getParent());
            Path lockPath = path.resolveSibling(path.getFileName() + ".cas-lock");
            try (var ignored = openLocked(lockPath)) {
                if (!Objects.equals(expected, read())) return false;
                Path temp = Files.createTempFile(path.getParent(), ".memory-", ".tmp");
                try {
                    try (var output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                        ByteBuffer bytes = StandardCharsets.UTF_8.encode(updated);
                        while (bytes.hasRemaining()) output.write(bytes);
                        output.force(true);
                    }
                    // Never degrade to a non-atomic overwrite: an unsupported backend fails closed.
                    replace(temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
                return true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            local.unlock();
        }
    }

    private static FileChannel openLocked(Path lockPath) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                FileChannel channel =
                        FileChannel.open(
                                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                boolean locked = false;
                try {
                    channel.lock();
                    locked = true;
                    return channel; // Closing the channel releases its lock.
                } finally {
                    if (!locked) channel.close();
                }
            } catch (AccessDeniedException denied) {
                // Windows may deny opening a lock file while another process holds it.
                if (attempt == 6) throw denied;
                backoff(attempt);
            }
        }
    }

    private void replace(Path temp) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException denied) {
                // Windows may briefly deny replacement while a reader holds the target open.
                if (attempt == 4) throw denied;
                backoff(attempt);
            }
        }
    }

    private static void backoff(int attempt) throws InterruptedIOException {
        try {
            Thread.sleep(10L << attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException error =
                    new InterruptedIOException("Interrupted during atomic memory update");
            error.initCause(interrupted);
            throw error;
        }
    }
}
