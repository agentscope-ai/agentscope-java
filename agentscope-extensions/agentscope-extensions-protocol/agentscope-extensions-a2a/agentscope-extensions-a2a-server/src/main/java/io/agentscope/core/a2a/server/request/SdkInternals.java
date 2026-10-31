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

package io.agentscope.core.a2a.server.request;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;

/**
 * The members of the A2A SDK that AgentScope reaches by reflection, because the SDK does not
 * expose them. They are kept here so that an SDK upgrade only has to check this class.
 */
final class SdkInternals {

    private SdkInternals() {}

    /**
     * Set the timeouts of a request handler. The SDK builder sets fixed timeouts of a few seconds,
     * too short for agents calling models, and offers no way to change them.
     *
     * @param handler the request handler
     * @param agentCompletionTimeoutSeconds how long a blocking request waits for the agent
     * @param consumptionCompletionTimeoutSeconds how long a blocking request then waits for the
     *     events to be consumed
     * @throws IllegalStateException if the SDK has no such fields
     */
    static void setTimeouts(
            DefaultRequestHandler handler,
            int agentCompletionTimeoutSeconds,
            int consumptionCompletionTimeoutSeconds) {
        try {
            setInt(handler, "agentCompletionTimeoutSeconds", agentCompletionTimeoutSeconds);
            setInt(
                    handler,
                    "consumptionCompletionTimeoutSeconds",
                    consumptionCompletionTimeoutSeconds);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set the A2A request timeouts", e);
        }
    }

    /**
     * Stop the thread that persists and dispatches events. Not public: the SDK stops it through
     * CDI.
     *
     * @param processor the event processor
     * @throws IllegalStateException if the SDK has no such method
     */
    static void stop(MainEventBusProcessor processor) {
        try {
            Method stop = MainEventBusProcessor.class.getDeclaredMethod("stop");
            stop.setAccessible(true);
            stop.invoke(processor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to stop the A2A event processor", e);
        }
    }

    private static void setInt(DefaultRequestHandler handler, String name, int value)
            throws ReflectiveOperationException {
        Field field = DefaultRequestHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(handler, value);
    }
}
