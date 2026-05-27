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
package io.agentscope.dataagent.tools.data;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the DataAgent toolkit defaults. Exposes a {@link DataSourceRegistry} (empty
 * {@link InMemoryDataSourceRegistry}) and {@link ChartRenderer} ({@link StubChartRenderer}) so
 * operators can override either independently — e.g. a Spring profile that wires a JDBC-backed
 * registry or a server-side PNG renderer.
 *
 * <p>The actual registration of the toolkit onto the main agent's toolkit lives in
 * {@link DataToolkitRegistrar} so the {@code @PostConstruct} cannot tangle with self-injection
 * of the {@code @Bean} methods defined here.
 */
@Configuration
public class DataToolkitConfig {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitConfig.class);

    @Bean
    @ConditionalOnMissingBean(DataSourceRegistry.class)
    public DataSourceRegistry inMemoryDataSourceRegistry() {
        log.info(
                "DataToolkitConfig: no DataSourceRegistry bean found, using empty"
                        + " InMemoryDataSourceRegistry");
        return new InMemoryDataSourceRegistry(List.of());
    }

    @Bean
    @ConditionalOnMissingBean(ChartRenderer.class)
    public ChartRenderer stubChartRenderer() {
        log.info("DataToolkitConfig: no ChartRenderer bean found, using StubChartRenderer");
        return new StubChartRenderer();
    }
}
