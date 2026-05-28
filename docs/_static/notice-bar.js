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

  var STORAGE_KEY = "agentscope-docs-site-notice";

  function dismiss(bar) {
    var id = bar.getAttribute("data-notice-id");
    if (!id) {
      return;
    }
    try {
      localStorage.setItem(STORAGE_KEY, id);
    } catch (e) {
      /* ignore quota / private mode */
    }
    document.documentElement.classList.add("docs-site-notice-dismissed");
  }

  function init() {
    var bar = document.querySelector(".docs-site-notice");
    if (!bar) {
      return;
    }
    var btn = bar.querySelector("[data-notice-dismiss]");
    if (btn) {
      btn.addEventListener("click", function () {
        dismiss(bar);
      });
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init, { once: true });
  } else {
    init();
  }
})();
