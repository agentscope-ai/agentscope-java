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

package io.agentscope.core.nacos.prompt;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosPromptListener;
import com.alibaba.nacos.api.ai.listener.NacosPromptEvent;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.ai.model.prompt.PromptVariable;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and renders Nacos prompt templates while keeping them updated through subscriptions.
 *
 * <p>Each prompt name and version or label selection has its own cached template and listener.
 * Rendering combines the template's variable defaults with the arguments supplied by the caller.
 * Missing or deleted templates use the caller's fallback, or an empty string when none is supplied.
 * Nacos errors are propagated unless the caller supplies a fallback.
 *
 * <p>Different selections load and render concurrently; initialization is serialized only for the
 * same selection. Callbacks update cached templates independently. Closing waits for in-flight reads
 * before cancelling subscriptions. Call {@link #close()} when the listener is no longer needed;
 * the supplied {@link AiService} remains managed by its owner.
 */
public class NacosPromptListener implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NacosPromptListener.class);
    private static final Prompt EMPTY_SENTINEL = new Prompt("", "", "");
    private final AiService aiService;
    private final Map<SubscriptionKey, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;

    /**
     * Creates a listener using an existing Nacos AI service.
     *
     * @param aiService the Nacos AI service, which remains managed by the caller
     * @throws NullPointerException if aiService is null
     */
    public NacosPromptListener(AiService aiService) {
        this.aiService = Objects.requireNonNull(aiService, "aiService");
    }

    /**
     * Get prompt template using its default variable values.
     *
     * @param promptKey the prompt key
     * @return rendered prompt string, or an empty string if the prompt is missing or empty
     * @throws NacosException if a Nacos service error occurs
     * @throws IllegalArgumentException if promptKey is null or blank
     * @throws IllegalStateException if this listener has been closed
     */
    public String getPrompt(String promptKey) throws NacosException {
        return getPrompt(promptKey, null);
    }

    /**
     * Get prompt template with variables for rendering.
     *
     * @param promptKey the prompt key
     * @param args the template variables, overriding template defaults; may be null
     * @return rendered prompt string, or an empty string if the prompt is missing or empty
     * @throws NacosException if a Nacos service error occurs
     * @throws IllegalArgumentException if promptKey is null or blank
     * @throws IllegalStateException if this listener has been closed
     */
    public String getPrompt(String promptKey, Map<String, String> args) throws NacosException {
        return getPrompt(promptKey, args, null);
    }

    /**
     * Get prompt template with optional default value.
     *
     * @param promptKey the prompt key
     * @param args the template variables, overriding template defaults; may be null
     * @param defaultValue the fallback template if the prompt is missing or empty, or a Nacos
     *     service error occurs; may be null
     * @return rendered prompt string or default value, or an empty string if the prompt is
     *     missing or empty and no default value is supplied
     * @throws NacosException if a Nacos service error occurs and defaultValue is null
     * @throws IllegalArgumentException if promptKey is null or blank
     * @throws IllegalStateException if this listener has been closed
     */
    public String getPrompt(String promptKey, Map<String, String> args, String defaultValue)
            throws NacosException {
        return getPrompt(promptKey, null, null, args, defaultValue);
    }

    /**
     * Get prompt template with version/label targeting and optional default value.
     *
     * <p>Version and label are trimmed; null or blank values are treated as unspecified.
     * Different version/label selections for the same prompt are tracked independently.
     *
     * @param promptKey the prompt key
     * @param version target prompt version (e.g. "1.0.0"), mutually exclusive with label
     * @param label target prompt label (e.g. "prod"), mutually exclusive with version
     * @param args the template variables, overriding template defaults; may be null
     * @param defaultValue the fallback template if the prompt is missing or empty, or a Nacos
     *     service error occurs; may be null
     * @return rendered prompt string or default value, or an empty string if the prompt is
     *     missing or empty and no default value is supplied
     * @throws NacosException if a Nacos service error occurs and defaultValue is null
     * @throws IllegalArgumentException if promptKey is null or blank, or both version and label
     *     are specified
     * @throws IllegalStateException if this listener has been closed
     */
    public String getPrompt(
            String promptKey,
            String version,
            String label,
            Map<String, String> args,
            String defaultValue)
            throws NacosException {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Prompt listener is closed");
            if (promptKey == null || promptKey.isBlank()) {
                throw new IllegalArgumentException("Prompt key cannot be blank");
            }
            version = normalizeSelector(version);
            label = normalizeSelector(label);
            if (version != null && label != null) {
                throw new IllegalArgumentException(
                        "Prompt version and label are mutually exclusive");
            }
            SubscriptionKey key = new SubscriptionKey(promptKey, version, label);
            Subscription subscription = subscriptions.computeIfAbsent(key, Subscription::new);
            synchronized (subscription) {
                if (!subscription.initialized) {
                    try {
                        Prompt initial =
                                aiService.subscribePrompt(promptKey, version, label, subscription);
                        // A callback, including deletion, can precede the initial response.
                        subscription.value.compareAndSet(
                                null, initial == null ? EMPTY_SENTINEL : initial);
                        subscription.initialized = true;
                    } catch (NacosException e) {
                        log.error(
                                "Failed to load prompt {} (Nacos error {})",
                                promptKey,
                                e.getErrCode(),
                                e);
                        if (defaultValue != null)
                            return render(new Prompt(null, null, defaultValue), args);
                        throw e;
                    }
                }
            }
            Prompt prompt = subscription.value.get();
            if (prompt == EMPTY_SENTINEL
                    || prompt.getTemplate() == null
                    || prompt.getTemplate().isEmpty()) {
                return defaultValue == null
                        ? ""
                        : render(new Prompt(null, null, defaultValue), args);
            }
            return render(prompt, args);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Cancels all prompt subscriptions created by this listener.
     *
     * <p>The supplied {@link AiService} is not shut down. Further calls to {@code getPrompt}
     * are rejected. If cancellation fails, a subsequent call to {@code close} retries the
     * remaining subscriptions. Closing waits for in-flight reads so a subscription being created
     * concurrently is also cancelled before this method returns.
     *
     * @throws NacosException if any subscription cannot be cancelled; additional failures are
     *     attached as suppressed exceptions
     */
    @Override
    public void close() throws NacosException {
        lifecycle.writeLock().lock();
        try {
            closed = true;
            NacosException failure = null;
            var iterator = subscriptions.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                SubscriptionKey key = entry.getKey();
                Subscription subscription = entry.getValue();
                subscription.active = false;
                try {
                    aiService.unsubscribePrompt(
                            key.promptKey(), key.version(), key.label(), subscription);
                    iterator.remove();
                } catch (NacosException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private static String normalizeSelector(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String render(Prompt prompt, Map<String, String> args) {
        Map<String, String> values = new HashMap<>();
        if (prompt.getVariables() != null) {
            for (PromptVariable variable : prompt.getVariables()) {
                if (variable.getDefaultValue() != null)
                    values.put(variable.getName(), variable.getDefaultValue());
            }
        }
        if (args != null) values.putAll(args);
        String result = prompt.getTemplate();
        for (var entry : values.entrySet()) {
            result =
                    result.replace(
                            "{{" + entry.getKey() + "}}",
                            entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    /** Identifies a normalized prompt selection so different versions and labels remain isolated. */
    private record SubscriptionKey(String promptKey, String version, String label) {}

    /** Holds the current template or deletion marker for one independently selected subscription. */
    private static final class Subscription extends AbstractNacosPromptListener {
        private final SubscriptionKey key;
        private final AtomicReference<Prompt> value = new AtomicReference<>();
        private boolean initialized;
        private volatile boolean active = true;

        private Subscription(SubscriptionKey key) {
            this.key = key;
        }

        /** {@inheritDoc} */
        @Override
        public void onEvent(NacosPromptEvent event) {
            if (!active || event == null || !key.promptKey().equals(event.getPromptKey())) return;
            Prompt prompt = event.getPrompt();
            if (prompt == null) {
                value.set(EMPTY_SENTINEL);
            } else if (prompt.getTemplate() != null) {
                value.set(prompt);
            } else {
                log.warn("Ignoring prompt event with null template for {}", key.promptKey());
            }
        }
    }
}
