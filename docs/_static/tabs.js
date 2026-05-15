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
    const path = window.location.pathname;
    return path.includes("/zh/") ? "zh" : "en";
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

  function getTabData(link, lang) {
    if (!link) {
      return null;
    }

    return {
      id: link.dataset.tabId,
      mirrorPaths: link.dataset.mirrorPaths === "true",
      prefixes: parseList(lang === "zh" ? link.dataset.prefixesZh : link.dataset.prefixesEn),
      sections: parseList(lang === "zh" ? link.dataset.sectionsZh : link.dataset.sectionsEn),
      url: lang === "zh" ? link.dataset.urlZh : link.dataset.urlEn,
    };
  }

  function matchesPrefix(currentPage, prefix) {
    if (!prefix) {
      return false;
    }

    const normalizedPrefix = prefix.endsWith("/") ? prefix : prefix.replace(/\/+$/, "");
    return currentPage === normalizedPrefix || currentPage.startsWith(normalizedPrefix);
  }

  function prefixMatchScore(prefix) {
    if (!prefix) {
      return 0;
    }

    return prefix.endsWith("/") ? prefix.length : prefix.length + 0.1;
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
          bestScore = score;
          bestLink = link;
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

  function clearSidebarGroupVisibility() {
    const root = getSidebarTreeRoot();
    if (!root) {
      return;
    }

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

    const captions = Array.from(root.querySelectorAll(".caption"));
    if (!captions.length) {
      return;
    }

    clearSidebarGroupVisibility();

    if (!activeLink) {
      captions.forEach((caption) => setSectionVisibility(caption, true));
      return;
    }

    const activeTab = getTabData(activeLink, lang);
    const visibleSections = new Set(activeTab && activeTab.sections ? activeTab.sections : []);

    captions.forEach((caption) => {
      const sectionName = caption.textContent.trim();
      setSectionVisibility(caption, visibleSections.has(sectionName));
    });
  }

  function updateLanguageSwitch(lang) {
    const currentLang = document.getElementById("current-lang");
    if (currentLang) {
      currentLang.textContent = lang === "zh" ? "中文" : "English";
    }

    const options = document.querySelectorAll(".language-option");
    options.forEach((option) => {
      const optionLang = option.textContent.trim() === "中文" ? "zh" : "en";
      option.classList.toggle("active", optionLang === lang);
    });

    document.body.setAttribute("data-current-lang", lang);
  }

  function resetHeaderLanguageMenuLayout(menu) {
    if (!menu) {
      return;
    }

    menu.style.position = "";
    menu.style.top = "";
    menu.style.right = "";
    menu.style.left = "";
    menu.style.bottom = "";
    menu.style.minWidth = "";
    menu.style.maxHeight = "";
    menu.style.overflowY = "";
    menu.classList.remove("docs-lang-menu--fixed");
  }

  function positionHeaderLanguageMenu() {
    const header = document.querySelector(".docs-site-header");
    if (!header) {
      return;
    }

    const selector = header.querySelector(".language-selector");
    const menu = header.querySelector(".language-menu");
    const button = header.querySelector(".language-button");
    if (!selector || !menu || !button) {
      return;
    }

    if (!selector.classList.contains("open")) {
      resetHeaderLanguageMenuLayout(menu);
      return;
    }

    const br = button.getBoundingClientRect();
    const gap = 6;
    menu.style.position = "fixed";
    menu.style.top = `${Math.round(br.bottom + gap)}px`;
    menu.style.right = `${Math.max(8, Math.round(window.innerWidth - br.right))}px`;
    menu.style.left = "auto";
    menu.style.bottom = "auto";
    menu.style.minWidth = `${Math.max(Math.round(br.width), 160)}px`;
    menu.style.maxHeight = `${Math.max(120, Math.round(window.innerHeight - br.bottom - gap - 16))}px`;
    menu.style.overflowY = "auto";
    menu.classList.add("docs-lang-menu--fixed");
  }

  function closeLanguageMenu() {
    const header = document.querySelector(".docs-site-header");
    const selector = header
      ? header.querySelector(".language-selector")
      : document.querySelector(".language-selector");
    const button = selector ? selector.querySelector(".language-button") : document.querySelector(".language-button");
    const menu = selector ? selector.querySelector(".language-menu") : document.querySelector(".language-menu");

    resetHeaderLanguageMenuLayout(menu);

    if (selector) {
      selector.classList.remove("open");
    }
    if (button) {
      button.setAttribute("aria-expanded", "false");
    }
  }

  function getFallbackTabUrl(lang) {
    const homeTab = getTabLinks().find((link) => link.dataset.tabId === "home");
    if (!homeTab) {
      return null;
    }

    return lang === "zh" ? homeTab.dataset.urlZh : homeTab.dataset.urlEn;
  }

  function buildLanguageTarget(lang) {
    const currentLang = getCurrentLanguage();
    if (currentLang === lang) {
      return null;
    }

    const activeLink = getActiveTabLink(currentLang, getCurrentPage());
    const activeTab = getTabData(activeLink, currentLang);
    const activeTargetUrl = lang === "zh" ? activeLink && activeLink.dataset.urlZh : activeLink && activeLink.dataset.urlEn;

    if (activeTab && activeTab.mirrorPaths) {
      return (
        window.location.pathname.replace(`/${currentLang}/`, `/${lang}/`) +
        window.location.search +
        window.location.hash
      );
    }

    if (activeTargetUrl && activeTargetUrl !== "#") {
      return activeTargetUrl;
    }

    return getFallbackTabUrl(lang);
  }

  function applyNavigation(lang) {
    const currentPage = getCurrentPage();
    const activeLink = getActiveTabLink(lang, currentPage);
    setActiveTab(activeLink);
    filterSidebarByTab(activeLink, lang);
    updateLanguageSwitch(lang);

    const isBlogArticle =
      currentPage.includes("/blogs/") && !currentPage.endsWith("blogs/index");
    document.body.classList.toggle("docs-blog-article-page", isBlogArticle);
  }

  function scheduleApplyNavigation() {
    const lang = getCurrentLanguage();
    const run = () => applyNavigation(lang);

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", run, { once: true });
    } else {
      run();
    }

    window.addEventListener("load", run, { once: true });
    window.requestAnimationFrame(() => window.requestAnimationFrame(run));
    setTimeout(run, 0);
    window.addEventListener("pageshow", (event) => {
      if (event.persisted) {
        run();
      }
    });
  }

  window.AgentScopeDocs = {
    applyNavigation,
  };

  window.toggleLanguageMenu = function toggleLanguageMenu() {
    const header = document.querySelector(".docs-site-header");
    const selector = header
      ? header.querySelector(".language-selector")
      : document.querySelector(".language-selector");
    const button = selector ? selector.querySelector(".language-button") : document.querySelector(".language-button");
    if (!selector || !button) {
      return false;
    }

    const isOpen = selector.classList.toggle("open");
    button.setAttribute("aria-expanded", isOpen ? "true" : "false");

    if (isOpen) {
      requestAnimationFrame(() => requestAnimationFrame(positionHeaderLanguageMenu));
    } else {
      const menu = selector.querySelector(".language-menu");
      resetHeaderLanguageMenuLayout(menu);
    }

    return false;
  };

  window.switchLanguage = function switchLanguage(lang, skipRedirect) {
    localStorage.setItem("preferred-language", lang);
    updateLanguageSwitch(lang);

    if (!skipRedirect) {
      const targetUrl = buildLanguageTarget(lang);
      if (targetUrl && targetUrl !== window.location.pathname) {
        window.location.href = targetUrl;
        return false;
      }
    }

    applyNavigation(lang);
    closeLanguageMenu();
    return false;
  };

  document.addEventListener("click", (event) => {
    if (!event.target.closest(".docs-site-header .language-selector")) {
      closeLanguageMenu();
    }
  });

  function bindHeaderLanguageMenuLayout() {
    const schedulePosition = () => {
      if (document.querySelector(".docs-site-header .language-selector.open")) {
        positionHeaderLanguageMenu();
      }
    };

    window.addEventListener("resize", schedulePosition, { passive: true });
    window.addEventListener("scroll", schedulePosition, true);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bindHeaderLanguageMenuLayout, { once: true });
  } else {
    bindHeaderLanguageMenuLayout();
  }

  scheduleApplyNavigation();
})();
