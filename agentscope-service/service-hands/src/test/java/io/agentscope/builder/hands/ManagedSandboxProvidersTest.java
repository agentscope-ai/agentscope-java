/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedLifecycles;
import io.agentscope.builder.runtime.managed.ManagedSandboxBindings;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedStateException;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.web.config.BuilderE2bProperties;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedSandboxProvidersTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path directory;
    private final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedCatalog catalog = mock(ManagedCatalog.class);
    private final RedisManagedInfrastructure redis = mock(RedisManagedInfrastructure.class);
    private final VaultCrypto crypto = mock(VaultCrypto.class);
    private DriverManagerDataSource source;
    private ManagedSandboxBindings bindings;
    private ManagedLifecycles lifecycles;

    @BeforeEach
    void setup() {
        source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:providers-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        new ManagedAdmissionStore(source, mapper).createSession(scope, "{}");
        bindings = new ManagedSandboxBindings(source);
        lifecycles = new ManagedLifecycles(source);
        when(catalog.resolve(scope))
                .thenReturn(
                        new SessionResolveResult(
                                null,
                                Map.of(),
                                null,
                                null,
                                null,
                                Map.of("AGENTS.md", "instructions"),
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null));
    }

    private ManagedSandboxProviders providers(boolean local) {
        return new ManagedSandboxProviders(
                catalog,
                mapper,
                crypto,
                redis,
                source,
                new BuilderE2bProperties(),
                16,
                local,
                directory.toString());
    }

    @Test
    void obtainingAnExecutorDoesNotCreateASandboxBeforeCancellationIsChecked() {
        try (var provider = new ProviderClose(providers(true))) {
            assertNotNull(provider.value.executor(scope));
            assertFalse(Files.exists(directory.resolve(scope.messageGroup())));
            verifyNoInteractions(redis, crypto);
        }
    }

    @Test
    void archivePreservesWorkspaceAndDeleteIsIdempotent() throws Exception {
        Path workspace = directory.resolve(scope.messageGroup());
        Files.createDirectories(workspace.resolve("nested"));
        Files.writeString(workspace.resolve("nested/result.txt"), "keep");
        try (var provider = new ProviderClose(providers(true))) {
            var archive = lifecycles.stage(scope, "archived", "actor", "archive");
            provider.value.cleanup(archive, () -> {});
            assertEquals("keep", Files.readString(workspace.resolve("nested/result.txt")));
            lifecycles.beginCleanup(archive);
            lifecycles.complete(archive);
            var delete = lifecycles.stage(scope, "deleted", "actor", "delete");
            provider.value.cleanup(delete, () -> {});
            provider.value.cleanup(delete, () -> {});
            assertFalse(Files.exists(workspace));
        }
    }

    @Test
    void deletingBorrowedBindingDoesNotCallTheExternalProvider() {
        bindings.borrow(scope, "provider/existing-sandbox");
        var delete = lifecycles.stage(scope, "deleted", "actor", "delete");
        try (var provider = new ProviderClose(providers(false))) {
            provider.value.cleanup(delete, () -> {});
            assertEquals("released", bindings.get(scope).status());
            var other = new ManagedScope("tenant", "namespace", "other-session");
            new ManagedAdmissionStore(source, mapper).createSession(other, "{}");
            bindings.borrow(other, "provider/existing-sandbox");
            assertEquals("borrowed", bindings.get(other).status());
            verifyNoInteractions(redis, crypto);
        }
    }

    @Test
    void unknownCreationIsNeverReplacedDuringDeletion() {
        bindings.begin(scope);
        var delete = lifecycles.stage(scope, "deleted", "actor", "delete");
        try (var provider = new ProviderClose(providers(false))) {
            assertEquals(
                    "sandbox_creation_unknown",
                    assertThrows(
                                    ManagedStateException.class,
                                    () -> provider.value.cleanup(delete, () -> {}))
                            .code());
            assertEquals("creating", bindings.get(scope).status());
            verifyNoInteractions(redis, crypto);
        }
    }

    private static final class ProviderClose implements AutoCloseable {
        private final ManagedSandboxProviders value;

        private ProviderClose(ManagedSandboxProviders value) {
            this.value = value;
        }

        @Override
        public void close() {
            value.close();
        }
    }
}
