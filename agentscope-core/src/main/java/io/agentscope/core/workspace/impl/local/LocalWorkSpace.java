/*
 * Copyright 2026-2027 the original author or authors.
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

package io.agentscope.core.workspace.impl.local;

import io.agentscope.core.message.DataBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.workspace.AbstractWorkspace;

import java.util.Optional;
import java.util.Set;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

public class LocalWorkSpace extends AbstractWorkspace {

    @Override
    public String getInstructions() {
        return "";
    }

    @Override
    public Set<String> listMcpClients() {
        return Set.of();
    }

    @Override
    public Optional<McpClientWrapper> getMcpClient(String mcpClientName) {
        return Optional.empty();
    }

    @Override
    public Mono<McpClientWrapper> addMcpClient(McpClientWrapper mcpClient) {
        return null;
    }

    @Override
    public Mono<McpClientWrapper> removeMcpClient(String mcpClientName) {
        return null;
    }

    @Override
    public String offloadContext() {
        return "";
    }

    @Override
    public String offloadToolResult() {
        return "";
    }

    @Override
    public DataBlock offloadMessage() {
        return null;
    }

    @Override
    public boolean isAlive() {
        return false;
    }

    @Override
    public void init() {

    }

    @Override
    public boolean reset() {
        return false;
    }

    @Override
    public void close() {

    }

    @Override
    public Set<String> listSkills() {
        return Set.of();
    }

    @Override
    public Optional<AgentSkill> getSkill(String skillId) {
        return Optional.empty();
    }

    @Override
    public AgentSkill addSkill(AgentSkill skill) {
        return null;
    }

    @Override
    public Optional<AgentSkill> removeSkill(String skillId) {
        return Optional.empty();
    }
}
