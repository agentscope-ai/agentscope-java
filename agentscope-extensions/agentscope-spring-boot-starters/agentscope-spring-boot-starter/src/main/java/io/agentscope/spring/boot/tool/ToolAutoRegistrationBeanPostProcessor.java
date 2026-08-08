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
package io.agentscope.spring.boot.tool;

import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * {@link BeanPostProcessor} that automatically registers beans annotated with
 * {@link ToolBean} into every {@link Toolkit} instance created by the Spring
 * container.
 *
 * <p>The processor scans for {@code @ToolBean}-annotated beans when a
 * {@code Toolkit} bean is initialized and calls
 * {@link Toolkit#registerTool(Object)} for each one. Since {@code Toolkit} is
 * typically prototype-scoped, each new instance receives the full set of
 * registered tools.
 *
 * <p>Beans are looked up lazily via
 * {@link ApplicationContext#getBeansWithAnnotation(Class)} so that
 * {@code @ToolBean} beans are not eagerly instantiated unless a
 * {@code Toolkit} is actually created.
 */
public class ToolAutoRegistrationBeanPostProcessor
        implements BeanPostProcessor, ApplicationContextAware {

    private static final Logger logger =
            LoggerFactory.getLogger(ToolAutoRegistrationBeanPostProcessor.class);

    private ApplicationContext applicationContext;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        if (bean instanceof Toolkit toolkit) {
            Map<String, Object> toolBeans =
                    applicationContext.getBeansWithAnnotation(ToolBean.class);
            for (Map.Entry<String, Object> entry : toolBeans.entrySet()) {
                logger.debug(
                        "Auto-registering @ToolBean '{}' into Toolkit '{}'",
                        entry.getKey(),
                        beanName);
                toolkit.registerTool(entry.getValue());
            }
        }
        return bean;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
