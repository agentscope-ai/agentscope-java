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

  /**
   * Detect the version slug for the current page from its path.
   * Single source of truth shared with tabs.js sidebar filtering.
   */
  function detectCurrentVersion() {
    var path = window.location.pathname;
    if (path.indexOf("/v2/") !== -1) return "v2";
    return "v1";
  }

  /**
   * The button label is rendered server-side from ``docs_version`` (a single
   * static value), so it always shows "v1" — even on /v2/ pages. Sync it to
   * the option whose href points at the current version, and mark that option
   * as active so the dropdown reads correctly.
   */
  function syncVersionUi() {
    var selector = document.getElementById("version-selector");
    var label = document.getElementById("current-version-label");
    if (!selector) return;

    var current = detectCurrentVersion();
    var options = selector.querySelectorAll(".version-option");
    options.forEach(function (opt) {
      var href = opt.getAttribute("href") || "";
      var optVersion = null;
      if (href.indexOf("/v2/") !== -1 || href === "/v2/" || /\/v2\/?$/.test(href)) {
        optVersion = "v2";
      } else if (href.indexOf("/v1/") !== -1 || href === "/v1/" || /\/v1\/?$/.test(href)) {
        optVersion = "v1";
      }
      var isCurrent = optVersion === current;
      opt.classList.toggle("active", isCurrent);
      opt.setAttribute("aria-selected", isCurrent ? "true" : "false");
      if (isCurrent && label) {
        // Show the slug only (e.g. "v2"), keeping the button compact.
        label.textContent = current;
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", syncVersionUi);
  } else {
    syncVersionUi();
  }

  /**
   * Toggle the version dropdown open/closed.
   * Returns false so onclick="return toggleVersionMenu()" never follows href.
   */
  function toggleVersionMenu() {
    var selector = document.getElementById("version-selector");
    var menu = document.getElementById("version-menu");
    var btn = selector && selector.querySelector(".version-button");
    if (!selector || !menu) return false;

    var isOpen = selector.classList.toggle("open");
    if (btn) btn.setAttribute("aria-expanded", String(isOpen));

    if (isOpen) {
      // Position the menu so it stays inside the viewport (same technique as
      // language-switch uses via tabs.js for the fixed-placement fallback).
      var rect = selector.getBoundingClientRect();
      menu.classList.add("docs-version-menu--fixed");
      menu.style.top = rect.bottom + window.scrollY + "px";
      menu.style.left = rect.left + window.scrollX + "px";
      menu.style.minWidth = Math.max(rect.width, 140) + "px";

      // Close on outside click
      document.addEventListener("click", closeOnOutsideClick, { once: true, capture: true });
    } else {
      menu.classList.remove("docs-version-menu--fixed");
      menu.style.top = "";
      menu.style.left = "";
      menu.style.minWidth = "";
    }
    return false;
  }

  function closeOnOutsideClick(event) {
    var selector = document.getElementById("version-selector");
    if (selector && !selector.contains(event.target)) {
      var menu = document.getElementById("version-menu");
      var btn = selector.querySelector(".version-button");
      selector.classList.remove("open");
      if (btn) btn.setAttribute("aria-expanded", "false");
      if (menu) {
        menu.classList.remove("docs-version-menu--fixed");
        menu.style.top = "";
        menu.style.left = "";
        menu.style.minWidth = "";
      }
    }
  }

  // Expose globally so the inline onclick in the template can call it.
  window.toggleVersionMenu = toggleVersionMenu;
})();
