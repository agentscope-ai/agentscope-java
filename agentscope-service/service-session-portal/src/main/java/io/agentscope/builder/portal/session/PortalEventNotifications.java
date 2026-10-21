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
package io.agentscope.builder.portal.session;

import io.agentscope.builder.portal.persistence.PortalWebhookRepository;
import io.agentscope.builder.web.managed.service.SessionEventNotifier;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coalesces a transaction's notifications and publishes only its committed public prefix. */
@Service
public class PortalEventNotifications {
    private final SessionEventNotifier notifier;
    private final PortalWebhookRepository webhooks;

    public PortalEventNotifications(
            SessionEventNotifier notifier, PortalWebhookRepository webhooks) {
        this.notifier = notifier;
        this.webhooks = webhooks;
    }

    public void committed(String session) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            webhooks.wake(session, System.currentTimeMillis());
            notifier.publish(session);
            return;
        }
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Pending pending) {
                if (pending.sessions.add(session))
                    webhooks.wake(session, System.currentTimeMillis());
                return;
            }
        }
        var pending = new Pending();
        pending.sessions.add(session);
        webhooks.wake(session, System.currentTimeMillis());
        TransactionSynchronizationManager.registerSynchronization(pending);
    }

    private final class Pending implements TransactionSynchronization {
        private final Set<String> sessions = new LinkedHashSet<>();

        @Override
        public void afterCommit() {
            sessions.forEach(notifier::publish);
        }
    }
}
