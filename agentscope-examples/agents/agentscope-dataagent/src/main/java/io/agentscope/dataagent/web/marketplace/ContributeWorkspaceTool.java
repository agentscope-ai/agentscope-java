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
package io.agentscope.dataagent.web.marketplace;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import java.util.Objects;

/**
 * Agent-facing tool for nominating a skill / subagent / memory snippet for promotion to the
 * shared workspace. The tool records a {@code PENDING} {@link ContributionEntity}; an admin must
 * approve before the payload is materialized under {@code ${dataagentHome}/shared/<type>/<path>}.
 *
 * <p>The agent's prompt is responsible for asking the user before calling this tool, and for
 * supplying the caller's {@code source_user_id} and (optionally) {@code source_agent_id} from
 * the active session context — this mirrors the {@code OutboundTool#agent_id} pattern where the
 * agent identifies itself per call. The admin reviewing the contribution sees the claimed
 * identity and the verbatim payload; admin approval is the only gate that materializes content.
 *
 * <p>Registered as a singleton onto the built-in {@code data-agent} main agent at startup by
 * {@link ContributionToolRegistrar}. User-custom agents do not get this tool by default;
 * operators can lift it onto custom agents through their definition's {@code toolsAllow}.
 */
public final class ContributeWorkspaceTool {

    private final MarketContributionService service;

    public ContributeWorkspaceTool(MarketContributionService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Tool(
            name = "contribute_to_workspace",
            description =
                    """
                    Nominate a skill, sub-agent, or memory snippet from the current workspace for \
                    promotion to the shared workspace, so other users of this DataAgent deployment \
                    can benefit from it. Submits a PENDING contribution that an admin must approve \
                    before it is materialised on disk. Ask the user before calling this tool — \
                    contributions cannot be retracted once approved. Returns "ok: contribution #N \
                    submitted, awaiting admin approval" on success, or an error string starting \
                    with "error:".\
                    """)
    public String contribute(
            @ToolParam(
                            name = "source_user_id",
                            description =
                                    "Identity of the user whose workspace this artifact came from."
                                            + " Take this from the active session context — admins"
                                            + " review the claimed identity before approving.")
                    String sourceUserId,
            @ToolParam(name = "target_type", description = "One of: skill | subagent | memory")
                    String targetType,
            @ToolParam(
                            name = "target_path",
                            description =
                                    "Relative path under shared/<target_type>/. For a skill, this"
                                        + " is the skill directory name (e.g. 'cohort-builder')."
                                        + " For a subagent, the file name (e.g."
                                        + " 'report-writer.md'). For memory, the snippet file name"
                                        + " (e.g. '2026-05-22-cohort-rules.md').")
                    String targetPath,
            @ToolParam(
                            name = "payload",
                            description =
                                    "Verbatim content of the artifact at nomination time. For a"
                                            + " skill, the SKILL.md body. For a subagent, the"
                                            + " markdown body. For memory, the snippet text.")
                    String payload,
            @ToolParam(
                            name = "source_agent_id",
                            description =
                                    "Optional agent id the artifact was harvested from; helps the"
                                            + " admin trace context.",
                            required = false)
                    String sourceAgentId,
            @ToolParam(
                            name = "rationale",
                            description =
                                    "One- or two-sentence explanation for the reviewing admin"
                                            + " describing why this is worth sharing.",
                            required = false)
                    String rationale) {
        try {
            ContributionEntity saved =
                    service.submit(
                            sourceUserId,
                            sourceAgentId,
                            targetType,
                            targetPath,
                            rationale,
                            payload);
            return "ok: contribution #" + saved.getId() + " submitted, awaiting admin approval";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
