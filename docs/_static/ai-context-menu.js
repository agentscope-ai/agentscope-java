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

  var SITE_URL = "https://java.agentscope.io";
  var MAX_PROMPT_CHARS = 10000;

  // ── i18n ──────────────────────────────────────────────────────────

  var LABELS = {
    en: {
      copyPage: "Copy page",
      viewMarkdown: "View as Markdown",
      openClaude: "Open in Claude",
      openChatGPT: "Open in ChatGPT",
      llmsTxt: "llms.txt",
      copied: "Copied!",
      promptPrefix: "Here is a documentation page from AgentScope Java",
      promptSuffix: "Please help me understand this documentation.",
    },
    zh: {
      copyPage: "复制页面",
      viewMarkdown: "查看 Markdown 源码",
      openClaude: "在 Claude 中打开",
      openChatGPT: "在 ChatGPT 中打开",
      llmsTxt: "llms.txt",
      copied: "已复制！",
      promptPrefix: "以下是 AgentScope Java 的一篇文档",
      promptSuffix: "请帮我理解这篇文档。",
    },
  };

  function detectLang() {
    return window.location.pathname.indexOf("/zh/") !== -1 ? "zh" : "en";
  }

  function t(key) {
    var lang = detectLang();
    return (LABELS[lang] && LABELS[lang][key]) || LABELS.en[key] || key;
  }

  // ── SVG icons (inline, no external deps) ──────────────────────────

  var ICONS = {
    clipboard:
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>',
    code:
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>',
    claude:
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M15.31 3.84l-4.12 14.32a.86.86 0 0 0 .59 1.07.87.87 0 0 0 1.07-.6l4.12-14.32a.87.87 0 0 0-.6-1.07.87.87 0 0 0-1.06.6zm-5.59 2.7L4.44 12l5.28 5.46a.87.87 0 0 1-.01 1.23.87.87 0 0 1-1.23-.01L2.6 12.61a.87.87 0 0 1 0-1.22l5.88-6.07a.87.87 0 0 1 1.23-.01.87.87 0 0 1 .01 1.23zm8.56 0l5.28 5.46-5.28 5.46a.87.87 0 0 0 .01 1.23.87.87 0 0 0 1.23-.01l5.88-6.07a.87.87 0 0 0 0-1.22l-5.88-6.07a.87.87 0 0 0-1.23-.01.87.87 0 0 0-.01 1.23z"/></svg>',
    chatgpt:
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M22.28 9.37a6.2 6.2 0 0 0-.54-5.11A6.27 6.27 0 0 0 15 1.26a6.2 6.2 0 0 0-4.69-2.12 6.27 6.27 0 0 0-5.98 4.33 6.2 6.2 0 0 0-4.14 3.01 6.27 6.27 0 0 0 .77 7.35 6.2 6.2 0 0 0 .54 5.11 6.27 6.27 0 0 0 6.74 3 6.2 6.2 0 0 0 4.69 2.12 6.27 6.27 0 0 0 5.98-4.33 6.2 6.2 0 0 0 4.14-3.01 6.27 6.27 0 0 0-.77-7.35z"/></svg>',
    file:
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>',
    chevron:
      '<svg width="8" height="24" viewBox="0 -9 3 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M0 0L3 3L0 6"/></svg>',
  };

  // ── Helpers ────────────────────────────────────────────────────────

  function getPageText() {
    var article = document.querySelector("article[role='main']");
    if (!article) return "";
    return article.innerText.trim();
  }

  function getPageTitle() {
    var h1 = document.querySelector("article h1");
    if (h1) {
      var clone = h1.cloneNode(true);
      var link = clone.querySelector(".headerlink");
      if (link) link.remove();
      return clone.textContent.trim();
    }
    return document.title;
  }

  function buildPrompt() {
    var title = getPageTitle();
    var text = getPageText().slice(0, MAX_PROMPT_CHARS);
    return (
      t("promptPrefix") +
      ' "' +
      title +
      '":\n\n' +
      text +
      "\n\n" +
      t("promptSuffix")
    );
  }

  function getSourceUrl() {
    var viewLink = document.querySelector(".view-this-page a");
    if (!viewLink) return null;
    var href = viewLink.getAttribute("href") || "";
    return href
      .replace("?plain=true", "")
      .replace("github.com", "raw.githubusercontent.com")
      .replace("/blob/", "/");
  }

  // ── Toast ──────────────────────────────────────────────────────────

  function showToast(msg) {
    var existing = document.querySelector(".ai-ctx-toast");
    if (existing) existing.remove();

    var toast = document.createElement("div");
    toast.className = "ai-ctx-toast";
    toast.textContent = msg;
    document.body.appendChild(toast);
    setTimeout(function () {
      toast.classList.add("ai-ctx-toast--hide");
      setTimeout(function () {
        toast.remove();
      }, 300);
    }, 1800);
  }

  // ── Actions ────────────────────────────────────────────────────────

  function copyPage() {
    var text = getPageText();
    if (!text) return;
    navigator.clipboard.writeText(text).then(function () {
      showToast(t("copied"));
    });
  }

  function viewMarkdown() {
    var url = getSourceUrl();
    if (url) {
      window.open(url, "_blank");
    }
  }

  function openInClaude() {
    var prompt = buildPrompt();
    var url = "https://claude.ai/new?q=" + encodeURIComponent(prompt);
    window.open(url, "_blank");
  }

  function openInChatGPT() {
    var prompt = buildPrompt();
    var url = "https://chatgpt.com/?q=" + encodeURIComponent(prompt);
    window.open(url, "_blank");
  }

  function openLlmsTxt() {
    var version = window.location.pathname.indexOf("/v1/") !== -1 ? "v1" : "v2";
    window.open(SITE_URL + "/" + version + "/llms.txt", "_blank");
  }

  // ── Dropdown items (shown in the "more" menu) ─────────────────────

  var DROPDOWN_ITEMS = [
    { key: "viewMarkdown", icon: "code", action: viewMarkdown },
    { type: "divider" },
    { key: "openClaude", icon: "claude", action: openInClaude },
    { key: "openChatGPT", icon: "chatgpt", action: openInChatGPT },
    { type: "divider" },
    { key: "llmsTxt", icon: "file", action: openLlmsTxt },
  ];

  // ── Menu rendering ─────────────────────────────────────────────────

  function buildMenu() {
    var wrapper = document.createElement("div");
    wrapper.className = "ai-ctx";

    // Left button: "Copy page" (primary action)
    var copyBtn = document.createElement("button");
    copyBtn.className = "ai-ctx__primary";
    copyBtn.type = "button";
    copyBtn.setAttribute("aria-label", t("copyPage"));
    copyBtn.innerHTML =
      '<span class="ai-ctx__primary-icon">' +
      ICONS.clipboard +
      "</span>" +
      "<span>" +
      t("copyPage") +
      "</span>";
    copyBtn.addEventListener("click", function (e) {
      e.stopPropagation();
      copyPage();
    });
    wrapper.appendChild(copyBtn);

    // Right button: "⋯" dropdown trigger
    var moreBtn = document.createElement("button");
    moreBtn.className = "ai-ctx__more";
    moreBtn.type = "button";
    moreBtn.setAttribute("aria-haspopup", "true");
    moreBtn.setAttribute("aria-expanded", "false");
    moreBtn.setAttribute("aria-label", "More actions");
    moreBtn.innerHTML =
      '<span class="ai-ctx__chevron">' + ICONS.chevron + "</span>";
    wrapper.appendChild(moreBtn);

    // Dropdown menu
    var menu = document.createElement("div");
    menu.className = "ai-ctx__menu";
    menu.setAttribute("role", "menu");

    for (var i = 0; i < DROPDOWN_ITEMS.length; i++) {
      var item = DROPDOWN_ITEMS[i];
      if (item.type === "divider") {
        var hr = document.createElement("div");
        hr.className = "ai-ctx__divider";
        menu.appendChild(hr);
        continue;
      }
      var entry = document.createElement("button");
      entry.className = "ai-ctx__item";
      entry.type = "button";
      entry.setAttribute("role", "menuitem");
      entry.innerHTML =
        '<span class="ai-ctx__icon">' +
        (ICONS[item.icon] || "") +
        "</span>" +
        '<span class="ai-ctx__label">' +
        t(item.key) +
        "</span>";
      entry.addEventListener(
        "click",
        (function (action) {
          return function (e) {
            e.stopPropagation();
            closeMenu(wrapper);
            action();
          };
        })(item.action)
      );
      menu.appendChild(entry);
    }

    wrapper.appendChild(menu);

    // Toggle dropdown
    moreBtn.addEventListener("click", function (e) {
      e.stopPropagation();
      var isOpen = wrapper.classList.toggle("ai-ctx--open");
      moreBtn.setAttribute("aria-expanded", String(isOpen));
      if (isOpen) {
        positionMenu(wrapper, menu);
        document.addEventListener("click", onOutsideClick, {
          once: true,
          capture: true,
        });
      }
    });

    return wrapper;
  }

  function positionMenu(wrapper, menu) {
    var rect = wrapper.getBoundingClientRect();
    menu.style.top = rect.bottom + window.scrollY + 4 + "px";
    menu.style.right =
      document.documentElement.clientWidth - rect.right + "px";
  }

  function closeMenu(wrapper) {
    wrapper.classList.remove("ai-ctx--open");
    var btn = wrapper.querySelector(".ai-ctx__more");
    if (btn) btn.setAttribute("aria-expanded", "false");
  }

  function onOutsideClick(e) {
    var openMenus = document.querySelectorAll(".ai-ctx--open");
    for (var i = 0; i < openMenus.length; i++) {
      if (!openMenus[i].contains(e.target)) {
        closeMenu(openMenus[i]);
      }
    }
  }

  // ── Mount ──────────────────────────────────────────────────────────

  function mount() {
    var container = document.querySelector(".content-icon-container");
    if (!container) return;

    var article = document.querySelector("article[role='main']");
    if (!article) return;

    var menuEl = buildMenu();
    container.insertBefore(menuEl, container.firstChild);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mount, { once: true });
  } else {
    mount();
  }
})();
