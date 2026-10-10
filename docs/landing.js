// Delegated handlers also work after Mintlify navigates without a full reload.
(() => {
  if (window.__agentScopeLanding) return;
  window.__agentScopeLanding = true;
  document.addEventListener('click', async (event) => {
    if (!(event.target instanceof Element)) return;
    const tab = event.target.closest('.agentscope-landing .hs-tab[data-panel]');
    if (tab) {
      const windowElement = tab.closest('.hs-window');
      if (!windowElement) return;
      windowElement.querySelectorAll('.hs-tab').forEach((item) => {
        const selected = item === tab;
        item.classList.toggle('active', selected);
        item.setAttribute('aria-pressed', String(selected));
      });
      windowElement.querySelectorAll('.hs-code-panel').forEach((panel) => {
        panel.style.display = panel.id === tab.dataset.panel ? '' : 'none';
      });
    }
    const button = event.target.closest('.agentscope-landing .hs-copy-btn');
    if (!button) return;
    const original = button.textContent;
    const chinese = window.location.pathname.includes('/zh/');
    try {
      await navigator.clipboard.writeText(button.dataset.copyBase64 ? atob(button.dataset.copyBase64) : button.dataset.copy || '');
      button.textContent = chinese ? '已复制' : 'Copied';
    } catch {
      button.textContent = chinese ? '复制失败，请手动复制' : 'Select and copy manually';
    }
    window.setTimeout(() => { button.textContent = original; }, 1800);
  });
})();
