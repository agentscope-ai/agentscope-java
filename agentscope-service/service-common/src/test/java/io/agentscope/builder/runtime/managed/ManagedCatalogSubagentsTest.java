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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.builder.runtime.protocol.RuntimeRequestContext;
import io.agentscope.builder.runtime.protocol.RuntimeSessionBootstrap;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import io.agentscope.core.session.SessionEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedCatalogSubagentsTest {
    @TempDir private Path managedCheckpointRoot;

    @Test
    void onlyDurableLineageMayDelegateASealedDefinitionAndNeverTheRootTaskCredential()
            throws Exception {
        var mapper = new ObjectMapper();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:child-catalog-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var admission = new ManagedAdmissionStore(source, mapper);
        var journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        var children = new ManagedSubagents(source, mapper, admission, journal);
        var root = new ManagedScope("tenant", "namespace", UUID.randomUUID().toString());
        var original =
                Map.<String, Object>of(
                        "session",
                        Map.of(
                                "id",
                                root.sessionId(),
                                "ownerId",
                                "owner",
                                "agentId",
                                "agent",
                                "externalKey",
                                "agent-task|original"),
                        "agentSnapshot",
                        Map.of("name", "parent", "system", "parent system"),
                        "definitionFiles",
                        Map.of("AGENTS.md", "frozen root definition"),
                        "environment",
                        Map.of("id", "environment", "type", "self_hosted"),
                        "executionContext",
                        Map.of("taskContext", Map.of("taskToken", "PRIVATE_ROOT_TASK_TOKEN")));
        var bootstrap =
                new RuntimeSessionBootstrap(
                        new RuntimeRequestContext(
                                1,
                                root.tenant(),
                                root.namespace(),
                                null,
                                root.sessionId(),
                                null,
                                "configuration",
                                "trace",
                                Map.of("user", "owner"),
                                null,
                                0),
                        "managed",
                        "revision",
                        original);
        var crypto = mock(VaultCrypto.class);
        when(crypto.decrypt("sealed-root")).thenReturn(mapper.writeValueAsString(bootstrap));
        String configuration = "{\"owner_id\":\"owner\",\"encrypted_definition\":\"sealed-root\"}";
        admission.createSession(root, configuration);
        String turn = admission.acceptTurn(root, "owner", "root", "{}").id();
        admission.reservePublication(root, turn);
        admission.confirmPublication(root, turn, "broker");
        admission.activate(root, turn, 1, "brain");
        var definition =
                new ManagedSubagentDefinition(
                        1,
                        root.messageGroup(),
                        "child",
                        "child system",
                        null,
                        4,
                        "null",
                        "{}",
                        "{}",
                        List.of(),
                        List.of());
        var spec =
                new ManagedSubagents.Specification(
                        "worker", "child prompt", "header", mapper.writeValueAsString(definition));
        var checkpoint =
                new SessionEvent(
                        1,
                        "checkpoint",
                        1,
                        1,
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        "{\"stateJson\":\"{}\"}");
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        root,
                                        turn,
                                        1,
                                        "brain",
                                        "writer",
                                        "intent",
                                        0,
                                        List.of(checkpoint),
                                        List.of(
                                                new ToolIntent(
                                                        "spawn",
                                                        ManagedSubagents.OPERATION,
                                                        mapper.writeValueAsString(
                                                                Map.of("subagent", spec)))),
                                        List.of(),
                                        null)));
        String task = "tool_" + ManagedScope.fingerprint(root.messageGroup(), turn, "spawn");
        var child = children.start(root, task);
        var catalog = new ManagedCatalog(admission, mapper, crypto, children);
        var resolved = catalog.resolve(child.child());
        assertEquals(child.child().sessionId(), resolved.session().id());
        assertEquals("owner", resolved.session().ownerId());
        assertEquals("environment", resolved.environment().id());
        assertEquals(Map.of("AGENTS.md", "frozen root definition"), resolved.definitionFiles());
        assertNull(resolved.session().externalKey());
        assertEquals(Map.of(), resolved.executionContext());
        assertEquals(definition, catalog.childDefinition(child.child()));
        assertEquals(original.get("executionContext"), catalog.resolve(root).executionContext());
        var unlinked =
                new ManagedScope(root.tenant(), root.namespace(), UUID.randomUUID().toString());
        admission.createSession(unlinked, configuration);
        assertEquals(
                "definition_scope_mismatch",
                assertThrows(ManagedStateException.class, () -> catalog.resolve(unlinked)).code());
        var tampered = mapper.readTree(admission.session(child.child()).configuration());
        ((ObjectNode) tampered).put("parent_session_id", "other");
        new JdbcTemplate(source)
                .update(
                        "UPDATE ma.session SET configuration=? WHERE scope_key=?",
                        mapper.writeValueAsString(tampered),
                        child.child().messageGroup());
        assertEquals(
                "subagent_definition_mismatch",
                assertThrows(ManagedStateException.class, () -> catalog.resolve(child.child()))
                        .code());
    }
}
