/*
 * Copyright The AgentScope Go Authors
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

  const HIDDEN_CLASS = "docs-nav-group-hidden";

  function getTabsContainer() {
    return document.querySelector(".docs-top-tabs");
  }

  function getSidebarTreeRoot() {
    return (
      document.querySelector(".sidebar-scroll .sidebar-tree") ||
      document.querySelector(".sidebar-tree")
    );
  }

  function getCurrentPage() {
    const container = getTabsContainer();
    if (container && container.dataset.currentPage) {
      return container.dataset.currentPage;
    }

    return window.location.pathname
      .replace(/\/+$/, "")
      .replace(/^\/+/, "")
      .replace(/\.html$/, "");
  }

  function getCurrentLanguage() {
    const container = getTabsContainer();
    if (container && container.dataset.currentLang) {
      return container.dataset.currentLang;
    }

    return window.location.pathname.includes("/zh/") ? "zh" : "en";
  }

  function parseList(value) {
    if (!value) {
      return [];
    }

    return value
      .split("|")
      .map((item) => item.trim())
      .filter(Boolean);
  }

  function getTabLinks() {
    return Array.from(document.querySelectorAll(".docs-top-tabs__link"));
  }

  function tabField(link, base, lang) {
    const suffix = lang === "zh" ? "Zh" : "En";
    return link.dataset[base + suffix];
  }

  function getTabData(link, lang) {
    if (!link) {
      return null;
    }

    return {
      id: link.dataset.tabId,
      prefixes: parseList(tabField(link, "prefixes", lang)),
      sections: parseList(tabField(link, "sections", lang)),
    };
  }

  function matchesPrefix(currentPage, prefix) {
    if (!prefix) {
      return false;
    }

    const normalizedPrefix = prefix.replace(/\/+$/, "");
    return (
      currentPage === normalizedPrefix ||
      currentPage.startsWith(normalizedPrefix + "/")
    );
  }

  function prefixMatchScore(prefix) {
    if (!prefix) {
      return 0;
    }

    return prefix.replace(/\/+$/, "").length;
  }

  function getActiveTabLink(lang, currentPage) {
    let bestLink = null;
    let bestScore = -1;

    for (const link of getTabLinks()) {
      const tabData = getTabData(link, lang);
      if (!tabData) {
        continue;
      }

      for (const prefix of tabData.prefixes) {
        if (!matchesPrefix(currentPage, prefix)) {
          continue;
        }

        const score = prefixMatchScore(prefix);
        if (score > bestScore) {
          bestLink = link;
          bestScore = score;
        }
      }
    }

    return bestLink;
  }

  function setActiveTab(link) {
    const activeTabId = link && link.dataset.tabId ? link.dataset.tabId : "";

    getTabLinks().forEach((tabLink) => {
      const isActive = tabLink.dataset.tabId === activeTabId;
      tabLink.classList.toggle("is-active", isActive);

      if (isActive) {
        tabLink.setAttribute("aria-current", "page");
      } else {
        tabLink.removeAttribute("aria-current");
      }
    });

    if (activeTabId) {
      document.body.dataset.currentTab = activeTabId;
    } else {
      delete document.body.dataset.currentTab;
    }
  }

  function clearSidebarGroupVisibility(root) {
    root.querySelectorAll(`.${HIDDEN_CLASS}`).forEach((node) => {
      node.classList.remove(HIDDEN_CLASS);
    });
  }

  function setSectionVisibility(caption, visible) {
    caption.classList.toggle(HIDDEN_CLASS, !visible);

    let sibling = caption.nextElementSibling;
    while (sibling && !sibling.classList.contains("caption")) {
      sibling.classList.toggle(HIDDEN_CLASS, !visible);
      sibling = sibling.nextElementSibling;
    }
  }

  function filterSidebarByTab(activeLink, lang) {
    const root = getSidebarTreeRoot();
    if (!root) {
      return;
    }

    clearSidebarGroupVisibility(root);

    const activeTab = activeLink ? getTabData(activeLink, lang) : null;
    const tabSections = activeTab ? new Set(activeTab.sections) : null;
    const captions = Array.from(root.querySelectorAll(".caption"));

    captions.forEach((caption) => {
      const sectionName = caption.textContent.trim();
      const visible = tabSections && tabSections.has(sectionName);
      setSectionVisibility(caption, visible);
    });
  }

  function applyNavigation() {
    const lang = getCurrentLanguage();
    const currentPage = getCurrentPage();
    const activeLink = getActiveTabLink(lang, currentPage);

    setActiveTab(activeLink);
    filterSidebarByTab(activeLink, lang);
  }

  function scheduleApplyNavigation() {
    const run = () => applyNavigation();

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", run, { once: true });
    } else {
      run();
    }

    window.addEventListener("load", run, { once: true });
    window.requestAnimationFrame(() => window.requestAnimationFrame(run));
    window.addEventListener("pageshow", (event) => {
      if (event.persisted) {
        run();
      }
    });
  }

  window.AgentScopeDocsTabs = {
    applyNavigation,
  };

  scheduleApplyNavigation();
})();
