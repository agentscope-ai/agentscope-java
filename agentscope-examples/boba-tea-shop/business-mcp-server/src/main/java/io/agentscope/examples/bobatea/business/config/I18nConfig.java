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

package io.agentscope.examples.bobatea.business.config;

import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.i18n.LocaleContextResolver;

/**
 * Internationalization (i18n) Configuration for WebFlux
 */
@Configuration
public class I18nConfig {

    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource =
                new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setCacheSeconds(3600);
        return messageSource;
    }

    @Bean
    public LocaleContextResolver localeContextResolver() {
        return new AcceptHeaderLocaleContextResolver();
    }

    /**
     * Custom LocaleContextResolver for WebFlux that reads Accept-Language header
     */
    public static class AcceptHeaderLocaleContextResolver implements LocaleContextResolver {

        @Override
        public LocaleContext resolveLocaleContext(ServerWebExchange exchange) {
            List<Locale> acceptLanguages =
                    exchange.getRequest().getHeaders().getAcceptLanguageAsLocales();
            Locale locale = acceptLanguages.isEmpty() ? DEFAULT_LOCALE : acceptLanguages.get(0);
            return new SimpleLocaleContext(locale);
        }

        @Override
        public void setLocaleContext(ServerWebExchange exchange, LocaleContext localeContext) {
            throw new UnsupportedOperationException(
                    "Cannot change locale - use a different locale context resolution strategy");
        }
    }
}
