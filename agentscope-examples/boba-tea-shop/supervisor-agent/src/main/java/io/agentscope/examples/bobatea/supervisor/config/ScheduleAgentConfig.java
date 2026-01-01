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

package io.agentscope.examples.bobatea.supervisor.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.bobatea.supervisor.tools.ScheduleAgentTools;
import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.xxljob.XxlJobAgentScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ScheduleAgentConfig.XxlJobProperties.class)
@ConditionalOnProperty(prefix = "xxl.job", name = "enabled", havingValue = "true")
public class ScheduleAgentConfig {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleAgentConfig.class);

    @Autowired private SupervisorAgentPromptConfig promptConfig;

    @Bean
    public AgentConfig dailyReportAgent(ModelConfig model, ScheduleAgentTools tools) {
        logger.info("DailyReportAgent initialized with ReactAgent");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        return RuntimeAgentConfig.builder()
                .name("daily_report_agent")
                .sysPrompt(promptConfig.getDailyReportAgentInstruction())
                .toolkit(toolkit)
                .modelConfig(model)
                .build();
    }

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties properties) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(properties.getAdmin().getAddresses());
        executor.setAccessToken(properties.getAccessToken());
        executor.setAppname(properties.getExecutor().getAppname());
        executor.setAddress(properties.getExecutor().getAddress());
        executor.setIp(properties.getExecutor().getIp());
        executor.setPort(properties.getExecutor().getPort());
        executor.setLogPath(properties.getExecutor().getLogPath());
        executor.setLogRetentionDays(properties.getExecutor().getLogRetentionDays());
        return executor;
    }

    @Bean
    public AgentScheduler agentScheduler(
            XxlJobSpringExecutor xxlJobExecutor,
            @Qualifier("dailyReportAgent") AgentConfig dailyReportAgent) {
        XxlJobAgentScheduler agentScheduler = new XxlJobAgentScheduler(xxlJobExecutor);
        agentScheduler.schedule(dailyReportAgent);
        return new XxlJobAgentScheduler(xxlJobExecutor);
    }

    /**
     * XXL-Job Configuration Properties
     */
    @ConfigurationProperties(prefix = "xxl.job")
    public static class XxlJobProperties {

        private Admin admin = new Admin();
        private String accessToken;
        private Executor executor = new Executor();

        public Admin getAdmin() {
            return admin;
        }

        public void setAdmin(Admin admin) {
            this.admin = admin;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public Executor getExecutor() {
            return executor;
        }

        public void setExecutor(Executor executor) {
            this.executor = executor;
        }

        /**
         * Admin Configuration
         */
        public static class Admin {
            private String addresses;

            public String getAddresses() {
                return addresses;
            }

            public void setAddresses(String addresses) {
                this.addresses = addresses;
            }
        }

        /**
         * Executor Configuration
         */
        public static class Executor {
            private String appname;
            private String address = "";
            private String ip = "";
            private int port = 0;
            private String logPath;
            private int logRetentionDays;

            public String getAppname() {
                return appname;
            }

            public void setAppname(String appname) {
                this.appname = appname;
            }

            public String getAddress() {
                return address;
            }

            public void setAddress(String address) {
                this.address = address;
            }

            public String getIp() {
                return ip;
            }

            public void setIp(String ip) {
                this.ip = ip;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public String getLogPath() {
                return logPath;
            }

            public void setLogPath(String logPath) {
                this.logPath = logPath;
            }

            public int getLogRetentionDays() {
                return logRetentionDays;
            }

            public void setLogRetentionDays(int logRetentionDays) {
                this.logRetentionDays = logRetentionDays;
            }
        }
    }
}
