// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

(() => {
  if (document.getElementById('agentscope-baidu-analytics')) return;

  window._hmt = window._hmt || [];
  const script = document.createElement('script');
  script.id = 'agentscope-baidu-analytics';
  script.src = 'https://hm.baidu.com/hm.js?9cc2f979c09fd25f67b57f89237e28a2';
  script.async = true;
  document.head.appendChild(script);

  // Mintlify switches pages without reloading the initial analytics script.
  let currentPath = window.location.pathname + window.location.search;
  function trackPageview() {
    const path = window.location.pathname + window.location.search;
    if (path === currentPath) return;
    currentPath = path;
    window._hmt.push(['_trackPageview', path]);
  }
  window.addEventListener('mintlify:navigate', trackPageview);
  window.addEventListener('popstate', trackPageview);

  // Older Mintlify clients do not emit mintlify:navigate.
  for (const method of ['pushState', 'replaceState']) {
    const original = window.history[method];
    window.history[method] = function (...args) {
      const result = original.apply(this, args);
      trackPageview();
      return result;
    };
  }
})();
