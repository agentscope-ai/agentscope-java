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

import java.util.List;

/**
 * Material handed to a {@link SkillPromotionGate} when a draft skill is being considered for
 * promotion. Includes the skill body, support files, telemetry record, and the result of the
 * pre-promote security scan.
 *
 * <p>{@code scriptFiles} is the sandbox-mode-relevant payload: under {@code SkillBox
 * .codeExecution()} every {@code scripts/*} file gets uploaded into the sandbox and executed.
 * Reviewers must see the head + sha256 of every script file before saying "approve".
 */
public record SkillCandidate(
        String name,
        String description,
        String skillMdContent,
        List<String> supportFilePaths,
        SkillUsageRecord usage,
        SkillSecurityScanner.ScanResult securityScan,
        List<ScriptFilePreview> scriptFiles) {

    public record ScriptFilePreview(
            String relPath, String headPreview, int totalLines, String sha256) {}
}
