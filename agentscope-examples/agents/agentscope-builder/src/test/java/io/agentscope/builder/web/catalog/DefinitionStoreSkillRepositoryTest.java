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
package io.agentscope.builder.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefinitionStoreSkillRepositoryTest {

    private DefinitionStore store;
    private DefinitionStoreSkillRepository repo;

    @BeforeEach
    void setUp() {
        store = new BaseStoreDefinitionStore(new InMemoryStore());
        repo = new DefinitionStoreSkillRepository(store, "alice", "bot");
    }

    @Test
    void putAndLoadSkill_survivesWithoutLocalDisk() {
        String markdown =
                """
                ---
                name: demo-skill
                description: A demo skill for definition store
                ---
                Do the demo thing carefully.
                """;
        store.putText(
                "alice", "bot", DefinitionStoreSkillRepository.skillMdPath("demo-skill"), markdown);
        store.putText(
                "alice",
                "bot",
                DefinitionStoreSkillRepository.skillFilePath("demo-skill", "references/a.md"),
                "ref body");

        assertThat(repo.listSkillNames()).containsExactly("demo-skill");
        AgentSkill skill = repo.getSkill("demo-skill");
        assertThat(skill.getName()).isEqualTo("demo-skill");
        assertThat(skill.getDescription()).contains("demo skill");
        assertThat(skill.getResources()).containsEntry("references/a.md", "ref body");
        assertThat(repo.getAllSkills()).hasSize(1);
    }

    @Test
    void deletePrefix_removesSkillTree() {
        store.putText(
                "alice",
                "bot",
                DefinitionStoreSkillRepository.skillMdPath("x"),
                """
                ---
                name: x
                description: x skill
                ---
                body
                """);
        store.deletePrefix("alice", "bot", "skills/x");
        assertThat(repo.listSkillNames()).isEmpty();
        assertThat(store.list("alice", "bot", "skills")).isEmpty();
    }

    @Test
    void list_filtersByPrefix() {
        store.putText("alice", "bot", "skills/a/SKILL.md", "a");
        store.putText("alice", "bot", "skills/b/SKILL.md", "b");
        store.putText("alice", "bot", "resources/c.txt", "c");
        List<String> skills = store.list("alice", "bot", "skills");
        assertThat(skills).containsExactlyInAnyOrder("skills/a/SKILL.md", "skills/b/SKILL.md");
    }
}
