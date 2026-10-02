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
package io.agentscope.harness.agent.transcript;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TranscriptStore} backed by a local / NAS filesystem directory.
 *
 * <p>Each segment is an immutable file under
 * {@code {root}/{tenant}/{agentId}/{sessionId}/events/}. POSIX append is used only when
 * compacting into a single object; normal writes create new files.
 */
public class FilesystemTranscriptStore implements TranscriptStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemTranscriptStore.class);

    private static final Pattern SEGMENT_NAME = Pattern.compile("^(\\d+)-(\\d+)-([^.]+)\\.jsonl$");

    private final Path root;

    public FilesystemTranscriptStore(Path root) {
        this.root = root;
    }

    @Override
    public String appendSegment(
            TranscriptRef ref, long seqStart, long seqEnd, String writerId, byte[] jsonl) {
        Path dir = eventsDir(ref);
        try {
            Files.createDirectories(dir);
            String name = seqStart + "-" + seqEnd + "-" + sanitize(writerId) + ".jsonl";
            Path file = dir.resolve(name);
            // CREATE_NEW ensures we never overwrite another writer's segment.
            Files.write(file, jsonl, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return keyFor(ref, name);
        } catch (IOException e) {
            throw new UncheckedIOException("appendSegment failed for " + ref.prefix(), e);
        }
    }

    @Override
    public List<SegmentInfo> listSegments(TranscriptRef ref) {
        Path dir = eventsDir(ref);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SegmentInfo> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path file : stream) {
                Matcher m = SEGMENT_NAME.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                Instant created;
                try {
                    created = Files.getLastModifiedTime(file).toInstant();
                } catch (IOException e) {
                    created = Instant.EPOCH;
                }
                out.add(
                        new SegmentInfo(
                                keyFor(ref, file.getFileName().toString()),
                                Long.parseLong(m.group(1)),
                                Long.parseLong(m.group(2)),
                                m.group(3),
                                created));
            }
        } catch (IOException e) {
            log.warn("listSegments failed for {}: {}", ref.prefix(), e.getMessage());
            return List.of();
        }
        out.sort(Comparator.comparingLong(SegmentInfo::seqStart).thenComparing(SegmentInfo::key));
        return out;
    }

    @Override
    public InputStream readSegment(String segmentKey) {
        Path file = root.resolve(segmentKey);
        try {
            return new ByteArrayInputStream(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("readSegment failed for " + segmentKey, e);
        }
    }

    @Override
    public void compact(TranscriptRef ref) {
        List<SegmentInfo> segments = listSegments(ref);
        if (segments.size() <= 1) {
            return;
        }
        long seqStart = segments.get(0).seqStart();
        long seqEnd = segments.get(segments.size() - 1).seqEnd();
        StringBuilder merged = new StringBuilder();
        for (SegmentInfo seg : segments) {
            try (InputStream in = readSegment(seg.key())) {
                merged.append(new String(in.readAllBytes()));
                if (!merged.isEmpty() && merged.charAt(merged.length() - 1) != '\n') {
                    merged.append('\n');
                }
            } catch (IOException e) {
                throw new UncheckedIOException("compact read failed for " + seg.key(), e);
            }
        }
        String compactedKey =
                appendSegment(ref, seqStart, seqEnd, "compacted", merged.toString().getBytes());
        for (SegmentInfo seg : segments) {
            if (seg.key().equals(compactedKey)) {
                continue;
            }
            try {
                Files.deleteIfExists(root.resolve(seg.key()));
            } catch (IOException e) {
                log.warn("Failed to delete compacted segment {}: {}", seg.key(), e.getMessage());
            }
        }
    }

    @Override
    public void delete(TranscriptRef ref) {
        Path dir = eventsDir(ref);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("delete transcript failed for {}: {}", ref.prefix(), e.getMessage());
        }
    }

    private Path eventsDir(TranscriptRef ref) {
        return root.resolve(ref.tenant())
                .resolve(ref.agentId())
                .resolve(ref.sessionId())
                .resolve("events");
    }

    private static String keyFor(TranscriptRef ref, String fileName) {
        return ref.prefix() + "/events/" + fileName;
    }

    private static String sanitize(String writerId) {
        if (writerId == null || writerId.isBlank()) {
            return "unknown";
        }
        return writerId.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
