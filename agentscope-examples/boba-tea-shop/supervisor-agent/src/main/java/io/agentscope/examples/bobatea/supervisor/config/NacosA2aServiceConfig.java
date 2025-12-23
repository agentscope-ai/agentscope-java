package io.agentscope.examples.bobatea.supervisor.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author xiweng.yy
 */
@Configuration
public class NacosA2aServiceConfig {

    @Value("${agentscope.mcp.nacos.server-addr}")
    private String serverAddress;

    @Value("${agentscope.mcp.nacos.namespace}")
    private String namespace;

    @Value("${agentscope.mcp.nacos.username}")
    private String username;

    @Value("${agentscope.mcp.nacos.password}")
    private String password;

    @Bean
    public AiService nacosA2aService() throws NacosException {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, serverAddress);
        properties.put(PropertyKeyConst.NAMESPACE, namespace);
        properties.put(PropertyKeyConst.USERNAME, username);
        properties.put(PropertyKeyConst.PASSWORD, password);
        return AiFactory.createAiService(properties);
    }
}
