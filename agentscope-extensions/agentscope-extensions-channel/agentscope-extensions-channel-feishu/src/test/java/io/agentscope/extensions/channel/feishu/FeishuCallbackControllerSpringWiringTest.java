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
package io.agentscope.extensions.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Constructor selection for the two wiring modes, exercised through Spring's own bean factory: with
 * a {@link FeishuTenantChannelManager} bean present the component-scanned controller must be built
 * by the multi-tenant constructor, and without one it must fall back to the static registry.
 */
class FeishuCallbackControllerSpringWiringTest {

    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

    @BeforeEach
    void enableAutowiredResolution() {
        AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();
        processor.setBeanFactory(beans);
        beans.addBeanPostProcessor(processor);
    }

    @Test
    void withoutAManagerBeanTheStaticWiringServesTheRequest() {
        FeishuCallbackController controller = beans.createBean(FeishuCallbackController.class);

        assertNull(controller.channelFor("no-such-channel"));
    }

    @Test
    void withAManagerBeanTheMultiTenantWiringServesTheRequest() {
        Map<String, FeishuChannelProperties> tenants = new HashMap<>();
        tenants.put(
                "acme",
                new FeishuChannelProperties(
                        "cli_acme",
                        "secret",
                        FeishuTestSupport.newEncryptKey(),
                        "vtok-acme",
                        null,
                        null));
        FeishuTenantChannelManager manager =
                new FeishuTenantChannelManager(
                        key -> Optional.ofNullable(tenants.get(key)),
                        ChannelConfig.of("feishu", "main"),
                        mock(Gateway.class));
        beans.registerSingleton("feishuTenantChannelManager", manager);

        FeishuCallbackController controller = beans.createBean(FeishuCallbackController.class);

        assertSame(manager.channelFor("acme").orElseThrow(), controller.channelFor("acme"));
    }
}
