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

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.LoggingEventBuilder;

/** An SLF4J logger decorator that prevents untrusted values from forging log records. */
public final class SanitizingLogger extends AbstractLogger {

    private static final long serialVersionUID = 1L;
    private static final String FQCN = SanitizingLogger.class.getName();

    private final Logger delegate;

    private SanitizingLogger(Logger delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.name = delegate.getName();
    }

    /**
     * Wrap a logger with CR/LF-neutralizing behavior.
     *
     * @param delegate logger that ultimately writes the event
     * @return a sanitizing logger, or {@code delegate} itself if it is already wrapped
     */
    public static Logger wrap(Logger delegate) {
        if (delegate instanceof SanitizingLogger) {
            return delegate;
        }
        return new SanitizingLogger(delegate);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return FQCN;
    }

    @Override
    protected void handleNormalizedLoggingCall(
            Level level, Marker marker, String message, Object[] arguments, Throwable throwable) {
        LoggingEventBuilder event = delegate.atLevel(level).setMessage(sanitizeText(message));
        if (marker != null) {
            event.addMarker(marker);
        }
        if (arguments != null) {
            for (Object argument : arguments) {
                event.addArgument(sanitizeArgument(argument));
            }
        }
        if (throwable != null) {
            event.setCause(throwable);
        }
        event.log();
    }

    private static Object sanitizeArgument(Object argument) {
        if (argument == null
                || argument instanceof Number
                || argument instanceof Boolean
                || argument instanceof Enum<?>) {
            return argument;
        }
        if (argument instanceof String string) {
            return sanitizeText(string);
        }
        if (argument instanceof Character character) {
            return sanitizeText(character.toString());
        }
        if (argument instanceof SafeArgument) {
            return argument;
        }
        return new SafeArgument(argument);
    }

    static String sanitizeText(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    @Override
    public boolean isTraceEnabled() {
        return delegate.isTraceEnabled();
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return delegate.isTraceEnabled(marker);
    }

    @Override
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return delegate.isDebugEnabled(marker);
    }

    @Override
    public boolean isInfoEnabled() {
        return delegate.isInfoEnabled();
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return delegate.isInfoEnabled(marker);
    }

    @Override
    public boolean isWarnEnabled() {
        return delegate.isWarnEnabled();
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return delegate.isWarnEnabled(marker);
    }

    @Override
    public boolean isErrorEnabled() {
        return delegate.isErrorEnabled();
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return delegate.isErrorEnabled(marker);
    }

    private static final class SafeArgument {
        private final Object value;

        private SafeArgument(Object value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return sanitizeText(MessageFormatter.format("{}", value).getMessage());
        }
    }
}
