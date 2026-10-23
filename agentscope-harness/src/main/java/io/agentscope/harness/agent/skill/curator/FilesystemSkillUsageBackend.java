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
package io.agentscope.harness.agent.skill.curator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Filesystem-backed {@link SkillUsageBackend} storing all records in a single JSON sidecar. */
final class FilesystemSkillUsageBackend implements SkillUsageBackend {

    private static final Logger log = LoggerFactory.getLogger(FilesystemSkillUsageBackend.class);

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final AbstractFilesystem filesystem;
    private final String relativePath;
    private final ReentrantLock lock = new ReentrantLock();

    FilesystemSkillUsageBackend(AbstractFilesystem filesystem, String relativePath) {
        this.filesystem = java.util.Objects.requireNonNull(filesystem, "filesystem");
        this.relativePath =
                relativePath != null && !relativePath.isBlank()
                        ? relativePath
                        : SkillUsageStore.DEFAULT_RELATIVE_PATH;
    }

    @Override
    public Map<String, SkillUsageRecord> loadAll() {
        try {
            ReadResult rr = filesystem.read(RuntimeContext.empty(), relativePath, 0, 0);
            if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                return new LinkedHashMap<>();
            }
            String body = rr.fileData().content();
            if (body.isBlank()) {
                return new LinkedHashMap<>();
            }
            Map<String, SkillUsageRecord> parsed =
                    JSON.readValue(
                            body, new TypeReference<LinkedHashMap<String, SkillUsageRecord>>() {});
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (Exception e) {
            log.debug("FilesystemSkillUsageBackend.loadAll() failed: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void saveAll(Map<String, SkillUsageRecord> data) {
        try {
            String json = JSON.writeValueAsString(data != null ? data : Map.of());
            filesystem.uploadFiles(
                    RuntimeContext.empty(),
                    List.of(
                            new AbstractMap.SimpleImmutableEntry<>(
                                    relativePath, json.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            log.warn("FilesystemSkillUsageBackend.saveAll() failed: {}", e.getMessage(), e);
        }
    }

    @Override
    public Optional<SkillUsageRecord> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(loadAll().get(name));
    }

    @Override
    public void mutate(String name, UnaryOperator<SkillUsageRecord> mutator) {
        if (name == null || name.isBlank()) {
            return;
        }
        lock.lock();
        try {
            Map<String, SkillUsageRecord> all = loadAll();
            boolean existed = all.containsKey(name);
            SkillUsageRecord current = existed ? all.get(name) : SkillUsageRecord.defaults();
            if (current == null) {
                current = SkillUsageRecord.defaults();
            }
            SkillUsageRecord updated = mutator.apply(current);
            if (updated == current) {
                return; // explicit no-op
            }
            if (updated == null) {
                if (!existed) {
                    return;
                }
                all.remove(name);
            } else {
                all.put(name, updated);
            }
            saveAll(all);
        } catch (Exception e) {
            log.debug("FilesystemSkillUsageBackend.mutate({}) failed: {}", name, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void replaceAll(Map<String, SkillUsageRecord> data) {
        lock.lock();
        try {
            saveAll(data);
        } finally {
            lock.unlock();
        }
    }
}
