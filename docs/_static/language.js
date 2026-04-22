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

(function () {
  "use strict";

  function getCurrentPageLanguage() {
    const path = window.location.pathname;
    if (path.includes("/zh/")) {
      return "zh";
    }
    return "en";
  }

  function autoSetLanguage() {
    const currentLang = getCurrentPageLanguage();
    const savedLang = localStorage.getItem("preferred-language");

    if (currentLang !== savedLang) {
      localStorage.setItem("preferred-language", currentLang);
    }

    setTimeout(() => {
      if (window.switchLanguage) {
        window.switchLanguage(currentLang, true);
      }
    }, 5);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", autoSetLanguage);
  } else {
    autoSetLanguage();
  }
})();
