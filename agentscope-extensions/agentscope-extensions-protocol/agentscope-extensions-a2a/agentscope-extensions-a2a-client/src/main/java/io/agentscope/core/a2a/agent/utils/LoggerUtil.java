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

package io.agentscope.core.a2a.agent.utils;

import io.a2a.client.ClientEvent;
import io.a2a.util.Utils;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.slf4j.Logger;

/**
 * A2A agent logger util.
 */
public class LoggerUtil {

    /**
     * Logs detail information of AgentScope events output from Agent.
     *
     * @param logger The Logger instance used for logging
     * @param event The event object to be logged from AgentScope Agent.
     */
    public static void logAgentEventDetail(Logger logger, Event event) {
        if (logger.isDebugEnabled()) {
            debug(logger, "Event: {}", event);
            logTextMsgDetail(logger, List.of(event.getMessage()));
        }
    }

    /**
     * Logs detailed information of A2A client events to the log
     *
     * @param logger The Logger instance used for logging
     * @param event  The client event object to be logged
     */
    public static void logA2aClientEventDetail(Logger logger, ClientEvent event) {
        if (logger.isTraceEnabled()) {
            try {
                String eventDetail = Utils.toJsonString(event);
                trace(logger, "\t {}", eventDetail);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Logs detailed information about the text content in a list of messages
     *
     * @param logger The Logger instance used for logging
     * @param msgs   The list of message objects containing content to be logged
     */
    public static void logTextMsgDetail(Logger logger, List<Msg> msgs) {
        if (logger.isDebugEnabled()) {
            msgs.stream()
                    .map(Msg::getContent)
                    .forEach(
                            contents ->
                                    debug(
                                            logger,
                                            "\t {}",
                                            contents.stream()
                                                    .filter(content -> content instanceof TextBlock)
                                                    .toList()));
        }
    }

    /**
     * Records TRACE level log information
     * <p>
     * This method first checks whether the logger has TRACE level enabled, and if so, records the formatted log
     * information. This avoids unnecessary string formatting operations when the log level is not enabled, improving
     * performance.
     * </p>
     *
     * @param logger The logger instance used to perform the actual logging operation
     * @param format The format string for the log message, following the format specification of SLF4J
     * @param args   The parameter array used to replace placeholders in the format string
     */
    public static void trace(Logger logger, String format, Object... args) {
        if (logger.isTraceEnabled()) {
            logger.trace(format, args);
        }
    }

    /**
     * Records DEBUG level log information
     * <p>
     * This method first checks whether the logger has DEBUG level enabled, and if so, records the formatted log
     * information. This avoids unnecessary string formatting operations when the log level is not enabled, improving
     * performance.
     * </p>
     *
     * @param logger The logger instance used to perform the actual logging operation
     * @param format The format string for the log message, following the format specification of SLF4J
     * @param args   The parameter array used to replace placeholders in the format string
     */
    public static void debug(Logger logger, String format, Object... args) {
        if (logger.isDebugEnabled()) {
            logger.debug(format, args);
        }
    }

    /**
     * Records INFO level log information
     * <p>
     * This method first checks whether the logger has INFO level enabled, and if so, records the formatted log
     * information. This avoids unnecessary string formatting operations when the log level is not enabled, improving
     * performance.
     * </p>
     *
     * @param logger The logger instance used to perform the actual logging operation
     * @param format The format string for the log message, following the format specification SLF4J
     * @param args   The parameter array used to replace placeholders in the format string
     */
    public static void info(Logger logger, String format, Object... args) {
        if (logger.isInfoEnabled()) {
            logger.info(format, args);
        }
    }

    /**
     * Records WARN level log information
     * <p>
     * This method first checks whether the logger has WARN level enabled, and if so, records the formatted log
     * information. This avoids unnecessary string formatting operations when the log level is not enabled, improving
     * performance.
     * </p>
     *
     * @param logger The logger instance used to perform the actual logging operation
     * @param format The format string for the log message, following the format specification SLF4J
     * @param args   The parameter array used to replace placeholders in the format string
     */
    public static void warn(Logger logger, String format, Object... args) {
        if (logger.isWarnEnabled()) {
            logger.warn(format, args);
        }
    }

    /**
     * Records ERROR level log information
     * <p>
     * This method first checks whether the logger has ERROR level enabled, and if so, records the formatted log
     * information. This avoids unnecessary string formatting operations when the log level is not enabled, improving
     * performance.
     * </p>
     *
     * @param logger The logger instance used to perform the actual logging operation
     * @param format The format string for the log message, following the format specification SLF4J
     * @param args   The parameter array used to replace placeholders in the format string
     */
    public static void error(Logger logger, String format, Object... args) {
        if (logger.isErrorEnabled()) {
            logger.error(format, args);
        }
    }
}
