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
package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

@Tag("unit")
class SanitizingLoggerTest {

    @Test
    void shouldNeutralizeLineBreaksInMessagesAndArguments() {
        CapturingLogger delegate = new CapturingLogger();
        Logger logger = SanitizingLogger.wrap(delegate);

        logger.info("first\nsecond {}", List.of("value\r\nforged"));

        assertEquals("first\\nsecond [value\\r\\nforged]", delegate.messages.get(0));
        assertFalse(delegate.messages.get(0).contains("\n"));
        assertFalse(delegate.messages.get(0).contains("\r"));
    }

    @Test
    void shouldPreserveThrowableAsEventCause() {
        CapturingLogger delegate = new CapturingLogger();
        Logger logger = SanitizingLogger.wrap(delegate);
        IllegalStateException failure = new IllegalStateException("failure\ntext");

        logger.error("operation failed for {}", "user\nname", failure);

        assertEquals("operation failed for user\\nname", delegate.messages.get(0));
        assertSame(failure, delegate.throwable);
    }

    private static final class CapturingLogger extends AbstractLogger {
        private final List<String> messages = new ArrayList<>();
        private Throwable throwable;

        private CapturingLogger() {
            this.name = "capturing";
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return CapturingLogger.class.getName();
        }

        @Override
        protected void handleNormalizedLoggingCall(
                Level level,
                Marker marker,
                String message,
                Object[] arguments,
                Throwable throwable) {
            messages.add(MessageFormatter.arrayFormat(message, arguments).getMessage());
            this.throwable = throwable;
        }

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public boolean isTraceEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public boolean isDebugEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled(Marker marker) {
            return true;
        }
    }
}
