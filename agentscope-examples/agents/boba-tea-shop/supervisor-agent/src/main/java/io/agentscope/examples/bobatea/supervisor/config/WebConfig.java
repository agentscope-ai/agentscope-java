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

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Web configuration for serving static frontend files.
 * Supports SPA routing by returning index.html for unmatched routes.
 *
 * - Docker: serves from /app/static/ (external file system)
 * - Local dev: serves from classpath:/static/
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${spring.web.resources.static-locations:classpath:/static/}")
    private String staticLocations;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(staticLocations.split(","))
                .resourceChain(true)
                .addResolver(new SpaPathResourceResolver());
    }

    /**
     * Custom PathResourceResolver that supports SPA routing.
     * Returns index.html for routes that don't match static files or API endpoints.
     */
    private class SpaPathResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = location.createRelative(resourcePath);

            // If resource exists and is readable, return it
            if (resource.exists() && resource.isReadable()) {
                return resource;
            }

            // For SPA: return index.html for non-API, non-asset routes
            if (shouldReturnIndexHtml(resourcePath)) {
                return getIndexHtml(location);
            }

            return null;
        }

        private boolean shouldReturnIndexHtml(String resourcePath) {
            // Don't handle API or actuator endpoints
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                return false;
            }
            // Don't handle paths that look like static files (have extension)
            if (resourcePath.contains(".")) {
                return false;
            }
            return true;
        }

        private Resource getIndexHtml(Resource location) throws IOException {
            Resource indexHtml = location.createRelative("index.html");
            if (indexHtml.exists() && indexHtml.isReadable()) {
                return indexHtml;
            }

            // Fallback: try classpath (local dev with repackaged JAR)
            Resource classpathIndex = new ClassPathResource("static/index.html");
            if (classpathIndex.exists() && classpathIndex.isReadable()) {
                return classpathIndex;
            }

            // Fallback: try file system (Docker)
            Resource fileIndex = new FileSystemResource("/app/static/index.html");
            if (fileIndex.exists() && fileIndex.isReadable()) {
                return fileIndex;
            }

            return null;
        }
    }
}
