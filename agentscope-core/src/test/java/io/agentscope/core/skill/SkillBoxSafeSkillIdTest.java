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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class SkillBoxSafeSkillIdTest {

    @Test
    @DisplayName("Should normalize unsafe chars in skill id for filesystem usage")
    void testUnifyToSafeSkillId() {
        assertEquals("my_skill_nacos_public", SkillBox.unifyToSafeSkillId("my_skill_nacos:public"));
        assertEquals("skill", SkillBox.unifyToSafeSkillId(""));
    }

    @Test
    @DisplayName("Should return default 'skill' when skillId is null")
    void testUnifyToSafeSkillIdWithNull() {
        assertEquals("skill", SkillBox.unifyToSafeSkillId(null));
    }

    @Test
    @DisplayName("Should return default 'skill' when skillId is blank (spaces)")
    void testUnifyToSafeSkillIdWithBlankSpaces() {
        assertEquals("skill", SkillBox.unifyToSafeSkillId("   "));
    }

    @Test
    @DisplayName("Should return default 'skill' when skillId is blank (tabs)")
    void testUnifyToSafeSkillIdWithBlankTabs() {
        assertEquals("skill", SkillBox.unifyToSafeSkillId("\t\t"));
    }

    @Test
    @DisplayName("Should return sanitized value when result is not blank")
    void testUnifyToSafeSkillIdWithNonBlankResult() {
        // Single unsafe char replaced
        assertEquals("my_skill", SkillBox.unifyToSafeSkillId("my:skill"));

        // Multiple unsafe chars replaced
        assertEquals("skill_name_with_colon", SkillBox.unifyToSafeSkillId("skill:name:with:colon"));

        // No unsafe chars - unchanged
        assertEquals("safe_skill_id", SkillBox.unifyToSafeSkillId("safe_skill_id"));
    }

    @Test
    @DisplayName("Should upload resources under normalized skill id directory")
    void testUploadUsesSafeSkillId(@TempDir Path tempDir) throws IOException {
        SkillBox skillBox = new SkillBox(new Toolkit());
        skillBox.codeExecution().workDir(tempDir.resolve("work").toString()).withWrite().enable();

        Map<String, String> resources = new HashMap<>();
        resources.put("scripts/main.py", "print('ok')");
        AgentSkill skill = new AgentSkill("my_skill", "desc", "content", resources, "nacos:public");
        skillBox.registerSkill(skill);

        skillBox.uploadSkillFiles();

        Path uploadDir = skillBox.getUploadDir();
        Path expected = uploadDir.resolve("my_skill_nacos_public/scripts/main.py");
        assertTrue(Files.exists(expected));
    }
}
