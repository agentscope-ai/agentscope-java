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

import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord.State;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillUsageStoreBaseStoreTest {

    @Test
    void baseStoreBackend_persistsAcrossStoreInstances() {
        InMemoryStore store = new InMemoryStore();
        SkillUsageStore writer = SkillUsageStore.baseStore(store);
        writer.markAgentDraft("persist", "session-1");
        writer.bumpUse("persist");

        SkillUsageStore reader = SkillUsageStore.baseStore(store);
        SkillUsageRecord rec = reader.get("persist").orElseThrow();
        assertEquals("agent-draft", rec.createdBy());
        assertEquals(1, rec.useCount());
        assertEquals("session-1", rec.sourceSessionId());
    }

    @Test
    void baseStoreBackend_casMutationsAccumulate() {
        InMemoryStore store = new InMemoryStore();
        SkillUsageStore usageStore = SkillUsageStore.baseStore(store);
        usageStore.markAgentDraft("hot", null);
        usageStore.bumpView("hot");
        usageStore.bumpView("hot");
        usageStore.markAgentCreated("hot", "reviewer", List.of("prod"));

        SkillUsageRecord rec = usageStore.get("hot").orElseThrow();
        assertEquals("agent", rec.createdBy());
        assertEquals(2, rec.viewCount());
        assertEquals(State.ACTIVE, rec.state());
        assertEquals(List.of("prod"), rec.environments());
    }

    @Test
    void baseStoreBackend_forgetRemovesKey() {
        InMemoryStore store = new InMemoryStore();
        SkillUsageStore usageStore = SkillUsageStore.baseStore(store);
        usageStore.markAgentDraft("gone", null);
        assertTrue(usageStore.get("gone").isPresent());

        usageStore.forget("gone");
        assertTrue(usageStore.get("gone").isEmpty());
    }

    @Test
    void baseStoreBackend_agentCreatedReportListsTrackedSkills() {
        InMemoryStore store = new InMemoryStore();
        SkillUsageStore usageStore = SkillUsageStore.baseStore(store);
        usageStore.markAgentDraft("d1", null);
        usageStore.save(java.util.Map.of("external", SkillUsageRecord.defaults()));

        var report = usageStore.agentCreatedReport();
        assertEquals(1, report.size());
        assertEquals("d1", report.get(0).getKey());
    }
}
