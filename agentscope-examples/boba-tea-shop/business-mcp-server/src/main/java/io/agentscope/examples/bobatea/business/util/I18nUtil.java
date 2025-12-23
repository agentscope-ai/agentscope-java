/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.examples.bobatea.business.util;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Internationalization utility class for retrieving localized messages
 */
@Component
public class I18nUtil {

    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    private static MessageSource messageSource;

    public I18nUtil(MessageSource messageSource) {
        I18nUtil.messageSource = messageSource;
    }

    /**
     * Get localized message by key using default locale (Simplified Chinese)
     *
     * @param key message key
     * @return localized message
     */
    public static String getMessage(String key) {
        return getMessage(key, DEFAULT_LOCALE);
    }

    /**
     * Get localized message by key with arguments using default locale
     *
     * @param key message key
     * @param args message arguments
     * @return localized message
     */
    public static String getMessage(String key, Object... args) {
        return getMessage(key, DEFAULT_LOCALE, args);
    }

    /**
     * Get localized message by key with specific locale
     *
     * @param key message key
     * @param locale target locale
     * @return localized message
     */
    public static String getMessage(String key, Locale locale) {
        return messageSource.getMessage(key, null, key, locale);
    }

    /**
     * Get localized message by key with arguments and specific locale
     *
     * @param key message key
     * @param locale target locale
     * @param args message arguments
     * @return localized message
     */
    public static String getMessage(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, key, locale);
    }
}
