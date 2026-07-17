/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.agentscope.core.a2a.agent.hitl;

/** The kind of durable human-in-the-loop handoff requested by the remote agent. */
public enum A2aHandoffType {
    USER_CONFIRM,
    EXTERNAL_EXECUTION
}
