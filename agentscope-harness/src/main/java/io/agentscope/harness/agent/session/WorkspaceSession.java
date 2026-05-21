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
package io.agentscope.harness.agent.session;

import io.agentscope.core.session.JsonSession;
import io.agentscope.core.state.SessionKey;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.nio.file.Path;
import java.util.List;

/**
 * Workspace-aware session that stores state under the agent's workspace directory.
 *
 * <p>Storage layout:
 *
 * <pre>
 * &lt;workspace&gt;/[namespace/]agents/&lt;agentId&gt;/context/&lt;sessionId&gt;/{key}.json
 * &lt;workspace&gt;/[namespace/]agents/&lt;agentId&gt;/context/&lt;sessionId&gt;/{key}.jsonl
 * </pre>
 *
 * <p>When a {@link NamespaceFactory} is provided, the namespace prefix (typically the userId)
 * is inserted between the workspace root and the {@code agents/} directory, ensuring per-user
 * isolation of session state on the local filesystem.
 */
public class WorkspaceSession extends JsonSession {

    private final Path workspace;
    private final String agentId;
    private final NamespaceFactory namespaceFactory;

    public WorkspaceSession(Path workspace, String agentId) {
        this(workspace, agentId, null);
    }

    public WorkspaceSession(Path workspace, String agentId, NamespaceFactory namespaceFactory) {
        super(workspace);
        this.workspace = workspace;
        this.agentId = agentId;
        this.namespaceFactory = namespaceFactory;
    }

    @Override
    protected Path getSessionDir(SessionKey sessionKey) {
        Path base = resolveContextDir(workspace, agentId, namespaceFactory);
        String identifier = sessionKey.toIdentifier();
        return base.resolve(identifier);
    }

    private static Path resolveContextDir(
            Path workspace, String agentId, NamespaceFactory namespaceFactory) {
        Path base = workspace;
        if (namespaceFactory != null) {
            List<String> ns = namespaceFactory.getNamespace();
            if (ns != null && !ns.isEmpty()) {
                base = base.resolve(String.join("/", ns));
            }
        }
        return base.resolve("agents").resolve(agentId).resolve("context");
    }
}
