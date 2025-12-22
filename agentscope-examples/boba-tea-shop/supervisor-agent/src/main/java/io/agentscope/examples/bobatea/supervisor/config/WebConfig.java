package io.agentscope.examples.bobatea.supervisor.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Web configuration for serving static frontend files.
 * Supports SPA routing by returning index.html for unmatched routes.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve static files from external location (Docker) or classpath (dev)
        registry.addResourceHandler("/**")
                .addResourceLocations("file:/app/static/", "classpath:/static/")
                .resourceChain(true)
                .addResolver(
                        new PathResourceResolver() {
                            @Override
                            protected Resource getResource(String resourcePath, Resource location)
                                    throws IOException {
                                Resource requestedResource = location.createRelative(resourcePath);

                                // If resource exists and is readable, return it
                                if (requestedResource.exists() && requestedResource.isReadable()) {
                                    return requestedResource;
                                }

                                // For SPA: return index.html for non-API, non-asset routes
                                if (!resourcePath.startsWith("api/")
                                        && !resourcePath.startsWith("actuator/")
                                        && !resourcePath.contains(".")) {
                                    // Try external location first, then classpath
                                    Resource indexHtml = location.createRelative("index.html");
                                    if (indexHtml.exists() && indexHtml.isReadable()) {
                                        return indexHtml;
                                    }
                                }

                                return null;
                            }
                        });
    }
}
