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
package io.agentscope.harness.agent.bus;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link MessageBus} implementation backed by {@link AbstractFilesystem}.
 *
 * <p>Queue claims rely on the filesystem backend's {@link AbstractFilesystem#write} operation
 * providing atomic create-if-absent behavior. Cross-process delivery also requires the backend to
 * be shared (for example a remote/KV-backed store); a pure local disk backend remains
 * single-process.
 *
 * <p>Mode D (pub/sub) is degraded to polling: {@link #subscribe} returns a {@code Flux} that emits
 * an empty signal every 3 seconds; {@link #publish} is a no-op.
 *
 * <p>File layout under {@code busRoot}:
 * <pre>
 * {busRoot}/
 *   queues/{key-hash}/
 *     {entryId}.payload    — Mode A payload, written before publication
 *     {entryId}.ready      — Mode A ready marker; legacy entries use {entryId}.json
 *     {entryId}.ready.claim — exclusive drain claim marker
 *   logs/{key-hash}/
 *     {entryId}.json       — Mode C entries (append-only)
 * </pre>
 *
 * <p>Claims are not leased. A crash after claiming an entry can leave it unavailable until
 * operator-led recovery; automatically reclaiming it could deliver the same entry twice.
 *
 * <p>New queue entries use {@code .payload} and {@code .ready} files. Older consumers that only
 * scan {@code .json} entries will not see entries created by this version during a mixed-version
 * rollout.
 */
public class WorkspaceMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMessageBus.class);
    private static final RuntimeContext RC = RuntimeContext.empty();
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    private final AbstractFilesystem fs;
    private final String busRoot;
    private final AtomicLong seq = new AtomicLong(System.nanoTime());

    /**
     * @param filesystem an {@link AbstractFilesystem} whose {@code write} operation atomically
     *     creates a path only when absent
     * @param busRoot    absolute path within the filesystem for bus data (e.g. {@code "/bus"})
     */
    public WorkspaceMessageBus(AbstractFilesystem filesystem, String busRoot) {
        this.fs = filesystem;
        this.busRoot = busRoot.endsWith("/") ? busRoot.substring(0, busRoot.length() - 1) : busRoot;
    }

    // ---- Mode A: drain queue ----

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.fromCallable(
                () -> {
                    String entryId = nextEntryId();
                    String dir = queueDir(key);
                    ensureDir(dir);
                    String payloadPath = dir + "/" + entryId + ".payload";
                    String readyPath = dir + "/" + entryId + ".ready";
                    String json = JsonUtils.getJsonCodec().toJson(payload);
                    WriteResult payloadWrite = fs.write(RC, payloadPath, json);
                    if (!payloadWrite.isSuccess()) {
                        throw new IllegalStateException(
                                "queuePush: failed to write payload "
                                        + payloadPath
                                        + " ("
                                        + payloadWrite.error()
                                        + ")");
                    }

                    WriteResult publish = fs.write(RC, readyPath, "");
                    if (!publish.isSuccess()) {
                        fs.delete(RC, payloadPath);
                        throw new IllegalStateException(
                                "queuePush: failed to publish entry "
                                        + readyPath
                                        + " ("
                                        + publish.error()
                                        + ")");
                    }
                    return entryId;
                });
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.fromCallable(
                () -> {
                    String dir = queueDir(key);
                    List<FileInfo> files = listQueueEntries(dir);
                    List<BusEntry> result = new ArrayList<>();
                    for (FileInfo fi : files) {
                        if (result.size() >= maxCount) {
                            break;
                        }
                        String claimPath = fi.path() + ".claim";
                        WriteResult claim = fs.write(RC, claimPath, "");
                        if (!claim.isSuccess()) {
                            if (!claim.isAlreadyExists()) {
                                if (!result.isEmpty()) {
                                    log.warn(
                                            "queueDrain: failed to claim entry {} ({}); returning"
                                                    + " {} already-drained entries",
                                            fi.path(),
                                            claim.error(),
                                            result.size());
                                    break;
                                }
                                throw new IllegalStateException(
                                        "queueDrain: failed to claim entry "
                                                + fi.path()
                                                + " ("
                                                + claim.error()
                                                + ")");
                            }
                            continue;
                        }
                        String content = readQueueEntryContent(fi.path());
                        if (content == null) {
                            // A transient read failure is retryable because no entry was returned.
                            log.warn(
                                    "queueDrain: failed to read entry {}, skipping without delete",
                                    fi.path());
                            releaseClaim(claimPath);
                            continue;
                        }

                        String entryId = extractEntryId(fi.path());
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload =
                                    JsonUtils.getJsonCodec().fromJson(content, Map.class);
                            result.add(new BusEntry(entryId, payload));
                        } catch (Exception e) {
                            // Corrupt JSON is unrecoverable; delete to avoid an infinite drain
                            // loop on the same bad entry, but log so it can be investigated.
                            log.warn(
                                    "queueDrain: corrupt JSON in entry {} ({}), deleting to"
                                            + " unblock queue",
                                    fi.path(),
                                    e.getMessage());
                        }

                        WriteResult deletion = deleteQueueEntry(fi.path());
                        if (deletion.isSuccess()) {
                            WriteResult claimDeletion = fs.delete(RC, claimPath);
                            if (!claimDeletion.isSuccess()) {
                                log.warn(
                                        "queueDrain: failed to delete claim {} ({}) after deleting"
                                                + " its entry",
                                        claimPath,
                                        claimDeletion.error());
                            }
                        } else {
                            log.warn(
                                    "queueDrain: failed to delete claimed entry {} ({});"
                                            + " retaining claim to prevent duplicate delivery",
                                    fi.path(),
                                    deletion.error());
                        }
                    }
                    return result;
                });
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        return Mono.fromRunnable(() -> fs.delete(RC, queueDir(key)));
    }

    @Override
    public Mono<Boolean> queuePeek(String key) {
        return Mono.fromCallable(() -> !listQueueEntries(queueDir(key)).isEmpty());
    }

    // ---- Mode C: replay log ----

    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return Mono.fromCallable(
                () -> {
                    String entryId = nextEntryId();
                    String dir = logDir(key);
                    ensureDir(dir);
                    String path = dir + "/" + entryId + ".json";
                    String json = JsonUtils.getJsonCodec().toJson(payload);
                    fs.write(RC, path, json);

                    if (maxLen > 0) {
                        List<FileInfo> files = listSorted(dir);
                        if (files.size() > maxLen) {
                            int excess = files.size() - maxLen;
                            for (int i = 0; i < excess; i++) {
                                fs.delete(RC, files.get(i).path());
                            }
                        }
                    }
                    return entryId;
                });
    }

    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        return Mono.fromCallable(
                () -> {
                    String dir = logDir(key);
                    List<FileInfo> files = listSorted(dir);
                    if (files.isEmpty()) {
                        return List.<BusEntry>of();
                    }

                    int startIdx = 0;
                    if (since != null) {
                        for (int i = 0; i < files.size(); i++) {
                            if (extractEntryId(files.get(i).path()).equals(since)) {
                                startIdx = i + 1;
                                break;
                            }
                        }
                    }

                    List<BusEntry> result = new ArrayList<>();
                    int endIdx = Math.min(startIdx + maxCount, files.size());
                    for (int i = startIdx; i < endIdx; i++) {
                        FileInfo fi = files.get(i);
                        String content = readFileContent(fi.path());
                        if (content != null) {
                            String entryId = extractEntryId(fi.path());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload =
                                    JsonUtils.getJsonCodec().fromJson(content, Map.class);
                            result.add(new BusEntry(entryId, payload));
                        }
                    }
                    return result;
                });
    }

    @Override
    public Mono<Void> logTrim(String key) {
        return Mono.fromRunnable(() -> fs.delete(RC, logDir(key)));
    }

    // ---- Mode D: degraded pub/sub ----

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return Mono.empty();
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        return Flux.interval(POLL_INTERVAL, Schedulers.boundedElastic())
                .map(tick -> Map.<String, Object>of());
    }

    // ---- Internal helpers ----

    private String queueDir(String key) {
        return busRoot + "/queues/" + hashKey(key);
    }

    private String logDir(String key) {
        return busRoot + "/logs/" + hashKey(key);
    }

    private String nextEntryId() {
        return String.format("%020d", seq.incrementAndGet());
    }

    static String hashKey(String key) {
        int hash = key.hashCode() & 0x7fffffff;
        return key.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + Integer.toHexString(hash);
    }

    private String extractEntryId(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        for (String suffix : List.of(".json", ".ready")) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    private List<FileInfo> listSorted(String dir) {
        return listSorted(dir, f -> f.path().endsWith(".json"));
    }

    private List<FileInfo> listQueueEntries(String dir) {
        return listSorted(dir, f -> f.path().endsWith(".json") || f.path().endsWith(".ready"));
    }

    private List<FileInfo> listSorted(String dir, Predicate<FileInfo> include) {
        if (!fs.exists(RC, dir)) {
            return List.of();
        }
        LsResult ls = fs.ls(RC, dir);
        if (!ls.isSuccess() || ls.entries() == null) {
            return List.of();
        }
        return ls.entries().stream()
                .filter(f -> !f.isDirectory() && include.test(f))
                .sorted(Comparator.comparing(FileInfo::path))
                .toList();
    }

    private String readQueueEntryContent(String path) {
        if (!path.endsWith(".ready")) {
            return readFileContent(path);
        }
        String payloadPath = path.substring(0, path.length() - ".ready".length()) + ".payload";
        return readFileContent(payloadPath);
    }

    private WriteResult deleteQueueEntry(String path) {
        if (path.endsWith(".ready")) {
            String payloadPath = path.substring(0, path.length() - ".ready".length()) + ".payload";
            WriteResult payloadDeletion = fs.delete(RC, payloadPath);
            if (!payloadDeletion.isSuccess()) {
                return payloadDeletion;
            }
        }
        return fs.delete(RC, path);
    }

    private void releaseClaim(String claimPath) {
        WriteResult release = fs.delete(RC, claimPath);
        if (!release.isSuccess()) {
            log.warn(
                    "queueDrain: failed to release claim {} ({}); entry will remain unavailable",
                    claimPath,
                    release.error());
        }
    }

    private String readFileContent(String path) {
        ReadResult rr = fs.read(RC, path, 0, Integer.MAX_VALUE);
        if (!rr.isSuccess() || rr.fileData() == null) {
            return null;
        }
        return rr.fileData().content();
    }

    private void ensureDir(String dir) {
        if (!fs.exists(RC, dir)) {
            fs.write(RC, dir + "/.keep", "");
        }
    }
}
