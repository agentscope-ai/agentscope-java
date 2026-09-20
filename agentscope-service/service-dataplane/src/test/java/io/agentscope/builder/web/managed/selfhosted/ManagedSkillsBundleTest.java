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
package io.agentscope.builder.web.managed.selfhosted;

import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class ManagedSkillsBundleTest {
    @TempDir Path root;

    @Test
    void usesPinnedFilesAndFilteredExternalRepositoriesWithoutReadingLegacyStore()
            throws Exception {
        var sessions = Mockito.mock(DataSessionService.class);
        var factory = Mockito.mock(HarnessAgentBuildService.class);
        var legacy = Mockito.mock(UserAgentDefinitionStore.class);
        var session = Mockito.mock(ManagedSessionDto.class);
        Mockito.when(session.agentId()).thenReturn("agent");
        Files.createDirectories(root.resolve("external/beta"));
        Files.writeString(
                root.resolve("external/beta/SKILL.md"),
                "---\nname: beta\ndescription: External beta\n---\nExternal content");
        var snapshot =
                Map.<String, Object>of(
                        "skills",
                        List.of(
                                Map.of("type", "workspace", "name", "alpha"),
                                Map.of("type", "workspace", "name", "beta")),
                        "skillRepositories",
                        List.of(Map.of("type", "filesystem", "path", "external")));
        var resolved =
                new SessionResolveResult(
                        session,
                        snapshot,
                        null,
                        null,
                        1,
                        Map.of(
                                "skills/alpha/SKILL.md",
                                "pinned alpha",
                                "skills/hidden/SKILL.md",
                                "hidden"),
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null);
        Mockito.when(sessions.resolve("session")).thenReturn(resolved);
        Mockito.when(factory.resolveSessionWorkspace(session, resolved)).thenReturn(root);
        var result = new SkillsBundleService(sessions, factory, legacy).bundleForSession("session");
        @SuppressWarnings("unchecked")
        var skills = (List<Map<String, Object>>) result.get("skills");
        Assertions.assertEquals(
                List.of("alpha", "beta"), skills.stream().map(s -> s.get("name")).toList());
        Assertions.assertEquals("pinned alpha", skills.get(0).get("skillContent"));
        Assertions.assertTrue(
                skills.get(1).get("skillContent").toString().contains("External content"));
        Mockito.verifyNoInteractions(legacy);
    }
}
