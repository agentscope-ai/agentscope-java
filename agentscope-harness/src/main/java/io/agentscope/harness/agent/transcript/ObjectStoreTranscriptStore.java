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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TranscriptStore} that writes immutable segment objects through {@link AbstractFilesystem}
 * (S3/OSS/NAS/sandbox backends that implement upload). Each flush uploads a new key — never
 * rewrites an existing object.
 */
public class ObjectStoreTranscriptStore implements TranscriptStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreTranscriptStore.class);

    private static final Pattern SEGMENT_NAME = Pattern.compile("^(\\d+)-(\\d+)-([^.]+)\\.jsonl$");

    private final AbstractFilesystem filesystem;
    private final RuntimeContext rc;
    private final String rootPrefix;

    public ObjectStoreTranscriptStore(AbstractFilesystem filesystem) {
        this(filesystem, RuntimeContext.empty(), "");
    }

    public ObjectStoreTranscriptStore(
            AbstractFilesystem filesystem, RuntimeContext rc, String rootPrefix) {
        this.filesystem = filesystem;
        this.rc = rc != null ? rc : RuntimeContext.empty();
        this.rootPrefix =
                rootPrefix == null || rootPrefix.isBlank()
                        ? ""
                        : (rootPrefix.endsWith("/") ? rootPrefix : rootPrefix + "/");
    }

    @Override
    public TranscriptStore withRuntimeContext(RuntimeContext rc) {
        if (rc == null) {
            return this;
        }
        return new ObjectStoreTranscriptStore(filesystem, rc, rootPrefix);
    }

    @Override
    public String appendSegment(
            TranscriptRef ref, long seqStart, long seqEnd, String writerId, byte[] jsonl) {
        String name = seqStart + "-" + seqEnd + "-" + sanitize(writerId) + ".jsonl";
        String key = rootPrefix + ref.prefix() + "/events/" + name;
        filesystem.uploadFiles(rc, List.of(Map.entry(key, jsonl)));
        return key;
    }

    @Override
    public List<SegmentInfo> listSegments(TranscriptRef ref) {
        String dir = rootPrefix + ref.prefix() + "/events";
        GlobResult glob = filesystem.glob(rc, "*.jsonl", dir);
        if (!glob.isSuccess() || glob.matches() == null) {
            return List.of();
        }
        List<SegmentInfo> out = new ArrayList<>();
        for (FileInfo fi : glob.matches()) {
            if (fi.path() == null) {
                continue;
            }
            String path = fi.path().replace('\\', '/');
            String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            Matcher m = SEGMENT_NAME.matcher(name);
            if (!m.matches()) {
                continue;
            }
            Instant created = Instant.EPOCH;
            if (fi.modifiedAt() != null && !fi.modifiedAt().isBlank()) {
                try {
                    created = Instant.parse(fi.modifiedAt());
                } catch (Exception ignored) {
                    created = Instant.EPOCH;
                }
            }
            String key = path;
            if (!rootPrefix.isEmpty() && !path.startsWith(rootPrefix)) {
                key = rootPrefix + path;
            }
            out.add(
                    new SegmentInfo(
                            key,
                            Long.parseLong(m.group(1)),
                            Long.parseLong(m.group(2)),
                            m.group(3),
                            created));
        }
        out.sort(Comparator.comparingLong(SegmentInfo::seqStart).thenComparing(SegmentInfo::key));
        return out;
    }

    @Override
    public InputStream readSegment(String segmentKey) {
        ReadResult read = filesystem.read(rc, segmentKey, 0, 0);
        if (!read.isSuccess() || read.fileData() == null || read.fileData().content() == null) {
            throw new IllegalStateException("segment not readable: " + segmentKey);
        }
        return new ByteArrayInputStream(read.fileData().content().getBytes(StandardCharsets.UTF_8));
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
                merged.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!merged.isEmpty() && merged.charAt(merged.length() - 1) != '\n') {
                    merged.append('\n');
                }
            } catch (Exception e) {
                throw new IllegalStateException("compact read failed for " + seg.key(), e);
            }
        }
        String compactedKey =
                appendSegment(
                        ref,
                        seqStart,
                        seqEnd,
                        "compacted",
                        merged.toString().getBytes(StandardCharsets.UTF_8));
        for (SegmentInfo seg : segments) {
            if (seg.key().equals(compactedKey)) {
                continue;
            }
            try {
                filesystem.delete(rc, seg.key());
            } catch (Exception e) {
                log.warn("Failed to delete compacted segment {}: {}", seg.key(), e.getMessage());
            }
        }
    }

    @Override
    public void delete(TranscriptRef ref) {
        for (SegmentInfo seg : listSegments(ref)) {
            try {
                filesystem.delete(rc, seg.key());
            } catch (Exception e) {
                log.warn("Failed to delete segment {}: {}", seg.key(), e.getMessage());
            }
        }
    }

    private static String sanitize(String writerId) {
        if (writerId == null || writerId.isBlank()) {
            return "unknown";
        }
        return writerId.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
