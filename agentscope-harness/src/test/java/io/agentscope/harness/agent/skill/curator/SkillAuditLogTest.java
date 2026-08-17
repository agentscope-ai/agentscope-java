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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillAuditLogTest {

    @TempDir Path workspace;
    private SkillAuditLog audit;

    @BeforeEach
    void setUp() {
        audit = new SkillAuditLog(new LocalFilesystem(workspace), null);
    }

    @Test
    void appendAndQuery_roundTrip() {
        audit.append(SkillAuditLog.manageEntry("agent", "csv-sum", "create", "draft", "SAFE"));
        audit.append(
                SkillAuditLog.promoteEntry("alice", "csv-sum", "Approve", "SAFE", List.of("prod")));

        // Today's day file exists
        String day =
                DateTimeFormatter.ofPattern("yyyy-MM-dd")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now());
        Path file = workspace.resolve("skills/.audit/" + day + ".jsonl");
        assertTrue(Files.exists(file));

        List<SkillAuditLog.Entry> all = audit.query(null, null);
        assertEquals(2, all.size());
        assertEquals("manage", all.get(0).op());
        assertEquals("promote", all.get(1).op());
    }

    @Test
    void filteredQuery() {
        audit.append(SkillAuditLog.manageEntry("agent", "a", "create", "draft", "SAFE"));
        audit.append(SkillAuditLog.manageEntry("agent", "b", "create", "draft", "SAFE"));
        audit.append(SkillAuditLog.promoteEntry("alice", "a", "Approve", "SAFE", List.of("prod")));

        List<SkillAuditLog.Entry> promotes = audit.query(null, e -> "promote".equals(e.op()));
        assertEquals(1, promotes.size());
        assertEquals("a", promotes.get(0).target());
    }

    @Test
    void multipleAppendsConcurrently_preserveAll() throws Exception {
        Runnable writer =
                () -> {
                    for (int i = 0; i < 20; i++) {
                        audit.append(
                                SkillAuditLog.manageEntry(
                                        "agent",
                                        "skill-" + Thread.currentThread().getName() + "-" + i,
                                        "create",
                                        "draft",
                                        "SAFE"));
                    }
                };
        Thread t1 = new Thread(writer, "t1");
        Thread t2 = new Thread(writer, "t2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        List<SkillAuditLog.Entry> all = audit.query(null, null);
        assertEquals(40, all.size(), "no entries lost under in-process concurrency");
    }
}
