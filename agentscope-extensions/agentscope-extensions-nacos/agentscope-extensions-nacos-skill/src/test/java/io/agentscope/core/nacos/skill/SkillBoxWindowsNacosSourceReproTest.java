/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.nacos.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Windows repro for Nacos source ":" issue with real {@link NacosSkillRepository} construction.
 */
@ExtendWith(MockitoExtension.class)
class SkillBoxWindowsNacosSourceReproTest {

    @Mock private AiService aiService;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("[Windows] real NacosSkillRepository source uploads via safe skill id path")
    void uploadSucceedsWithRealNacosSkillRepository(@TempDir Path tempDir)
            throws NacosException, IOException {
        NacosSkillRepository repository = new NacosSkillRepository(aiService, "public");
        when(aiService.downloadSkillZip("repro"))
                .thenReturn(createSkillZip("repro", "desc", "content", "scripts/main.py", "ok"));

        AgentSkill skill = repository.getSkill("repro");
        assertEquals("nacos:public", skill.getSource());

        SkillBox skillBox = new SkillBox(new Toolkit());
        skillBox.registerSkill(skill);
        skillBox.codeExecution()
                .workDir(tempDir.resolve("work").toString())
                .withShell()
                .withRead()
                .withWrite()
                .enable();

        skillBox.uploadSkillFiles();

        // SkillBox normalizes skillId for filesystem safety (':' -> '_').
        Path expected = skillBox.getUploadDir().resolve("repro_nacos_public/scripts/main.py");
        assertTrue(expected.toFile().exists(), "Resource should be uploaded under normalized path");
    }

    private static byte[] createSkillZip(
            String name,
            String description,
            String skillContent,
            String resourcePath,
            String resourceContent)
            throws IOException {
        String root = "skill-package";
        String skillMd =
                "---\n"
                        + "name: "
                        + name
                        + "\n"
                        + "description: "
                        + description
                        + "\n"
                        + "---\n"
                        + skillContent;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(root + "/SKILL.md"));
            zos.write(skillMd.getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(root + "/" + resourcePath));
            zos.write(resourceContent.getBytes());
            zos.closeEntry();

            zos.finish();
            return baos.toByteArray();
        }
    }
}
