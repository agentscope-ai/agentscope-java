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
package io.agentscope.examples.werewolf.localization;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
public class LocalizationFactory {

    private final MessageSource messageSource;

    public LocalizationFactory(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public LocalizationBundle createBundle(Locale locale) {
        return new LocalizationBundle(
                locale,
                new MessageSourcePromptProvider(messageSource, locale),
                new MessageSourceGameMessages(messageSource, locale),
                new MessageSourceLanguageConfig(messageSource, locale));
    }

    public LocalizationBundle createBundle(String langTag) {
        Locale locale = parseLocale(langTag);
        return createBundle(locale);
    }

    private Locale parseLocale(String langTag) {
        if (langTag == null || langTag.isEmpty()) {
            return Locale.CHINA;
        }
        Locale locale = Locale.forLanguageTag(langTag);
        if (locale.getLanguage().isEmpty()) {
            return Locale.CHINA;
        }
        return locale;
    }
}
