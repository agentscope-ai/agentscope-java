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
package io.agentscope.harness.agent.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.state.StateModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that automatically persists agent state to the session after each call.
 *
 * <p>Handles both {@link PostCallEvent} (success path) and {@link ErrorEvent} (error path) to
 * ensure state is saved regardless of outcome. The save is best-effort: failures are logged but
 * do not propagate exceptions.
 *
 * <p>Session and SessionKey are obtained from the {@link RuntimeContext} set per call. When no
 * session is configured in the context,
 * the hook is a no-op.
 *
 * <p>Priority is set to 900 (low) so this hook runs after other hooks like
 * {@link MemoryFlushHook} have completed their work.
 */
public class SessionPersistenceHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistenceHook.class);

    private RuntimeContext runtimeContext;

    @Override
    public void setRuntimeContext(RuntimeContext ctx) {
        this.runtimeContext = ctx;
    }

    @Override
    public int priority() {
        return 900;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostCallEvent || event instanceof ErrorEvent) {
            autoSave(event.getAgent());
        }
        return Mono.just(event);
    }

    private void autoSave(Agent agent) {
        RuntimeContext ctx = this.runtimeContext;
        if (ctx == null || ctx.getSession() == null || ctx.getSessionKey() == null) {
            return;
        }
        if (agent instanceof StateModule sm) {
            try {
                sm.saveTo(ctx.getSession(), ctx.getSessionKey());
                log.debug("Auto-saved session state for agent '{}'", agent.getName());
            } catch (Exception e) {
                log.warn(
                        "Auto-save session failed for agent '{}': {}",
                        agent.getName(),
                        e.getMessage());
            }
        }
    }
}
