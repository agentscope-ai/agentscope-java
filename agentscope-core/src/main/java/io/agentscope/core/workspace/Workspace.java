/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.agentscope.core.workspace;

import io.agentscope.core.message.DataBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.Optional;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */
public interface Workspace extends Sandbox, OffLoader, Mcp, SkillCatalog {

    default String workspaceId() {
        return sandboxId();
    }

    String workspaceRoot();

    String getInstructions();
}

/** Contract for the workspace sandbox lifecycle. */
interface Sandbox extends AutoCloseable {

    /**
     * Gets the sandbox id.
     *
     * @return the current sandbox id.
     */
    String sandboxId();

    /**
     * Checks whether the workspace is still alive.
     *
     * @return true when the workspace is alive, false otherwise.
     */
    boolean isAlive();

    /** Initializes the workspace sandbox. */
    void init();

    /** Resets the workspace sandbox. */
    boolean reset();
}

/** Persists data that should be moved out of the active model context. */
interface OffLoader {

    String offloadContext();

    String offloadToolResult();

    DataBlock offloadMessage();
}

/** Contract for managing MCP clients available in a workspace. */
interface Mcp {

    /**
     * Lists the names of MCP clients registered in the current workspace.
     *
     * @return the MCP client names.
     */
    Set<String> listMcpClients();

    /**
     * Looks up an MCP client by name.
     *
     * @param mcpClientName the MCP client name.
     * @return the matching MCP client, or empty when it does not exist.
     */
    Optional<McpClientWrapper> getMcpClient(String mcpClientName);

    /**
     * Registers an MCP client and returns it after the asynchronous initialization chain
     * completes.
     *
     * @param mcpClient the MCP client wrapper.
     * @return the registered MCP client.
     */
    Mono<McpClientWrapper> addMcpClient(McpClientWrapper mcpClient);

    /**
     * Removes the specified MCP client and returns the removed client.
     *
     * @param mcpClientName the MCP client name.
     * @return the removed MCP client, or an empty Mono when the client does not exist.
     */
    Mono<McpClientWrapper> removeMcpClient(String mcpClientName);
}

/** Contract for managing skills available in a workspace. */
interface SkillCatalog {

    /**
     * Lists the skill ids registered in the current workspace.
     *
     * @return the registered skill ids.
     */
    Set<String> listSkills();

    /**
     * Looks up a skill by id.
     *
     * @param skillId the skill id.
     * @return the matching skill, or empty when it does not exist.
     */
    Optional<AgentSkill> getSkill(String skillId);

    /**
     * Registers a skill and returns the actual registered skill.
     *
     * @param skill the skill instance.
     * @return the registered skill.
     */
    AgentSkill addSkill(AgentSkill skill);

    /**
     * Removes the specified skill and returns the removed skill.
     *
     * @param skillId the skill id.
     * @return the removed skill, or empty when the skill does not exist.
     */
    Optional<AgentSkill> removeSkill(String skillId);
}
