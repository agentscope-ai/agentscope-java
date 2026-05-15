(function () {
  'use strict';

  /* ── Styles ────────────────────────────────────────────────────── */
  var style = document.createElement('style');
  style.textContent = [
    '.mermaid-zoom-wrap {',
    '  position: relative;',
    '  display: block;',
    '}',
    '.mermaid-zoom-btn {',
    '  position: absolute;',
    '  top: 6px;',
    '  right: 6px;',
    '  z-index: 10;',
    '  display: flex;',
    '  align-items: center;',
    '  justify-content: center;',
    '  width: 28px;',
    '  height: 28px;',
    '  background: rgba(255,255,255,0.90);',
    '  border: 1px solid #d0d0d0;',
    '  border-radius: 5px;',
    '  cursor: pointer;',
    '  font-size: 15px;',
    '  line-height: 1;',
    '  opacity: 0;',
    '  transition: opacity 0.18s ease;',
    '  box-shadow: 0 1px 4px rgba(0,0,0,0.12);',
    '}',
    '.mermaid-zoom-btn:hover {',
    '  background: rgba(255,255,255,1);',
    '  border-color: #aaa;',
    '}',
    '.mermaid-zoom-wrap:hover .mermaid-zoom-btn {',
    '  opacity: 1;',
    '}',
    /* ── Modal overlay ── */
    '.mermaid-modal-overlay {',
    '  position: fixed;',
    '  inset: 0;',
    '  background: rgba(0,0,0,0.60);',
    '  z-index: 99999;',
    '  display: flex;',
    '  align-items: center;',
    '  justify-content: center;',
    '  cursor: zoom-out;',
    '  animation: mermaid-fade-in 0.15s ease;',
    '}',
    '@keyframes mermaid-fade-in {',
    '  from { opacity: 0; }',
    '  to   { opacity: 1; }',
    '}',
    '.mermaid-modal-inner {',
    '  position: relative;',
    '  background: #fff;',
    '  border-radius: 8px;',
    '  padding: 24px;',
    '  max-width: 92vw;',
    '  max-height: 88vh;',
    '  overflow: auto;',
    '  cursor: default;',
    '  box-shadow: 0 8px 40px rgba(0,0,0,0.30);',
    '}',
    '.mermaid-modal-inner svg {',
    '  display: block;',
    '  max-width: 100%;',
    '  height: auto;',
    '}',
    '.mermaid-modal-close {',
    '  position: absolute;',
    '  top: 10px;',
    '  right: 12px;',
    '  background: none;',
    '  border: none;',
    '  font-size: 22px;',
    '  cursor: pointer;',
    '  line-height: 1;',
    '  color: #555;',
    '  padding: 2px 6px;',
    '  border-radius: 4px;',
    '}',
    '.mermaid-modal-close:hover { color: #000; background: #f0f0f0; }',
  ].join('\n');
  document.head.appendChild(style);

  /* ── Wrap a single SVG with zoom button ────────────────────────── */
  function wrapSvg(svg) {
    var parent = svg.parentElement;
    if (!parent || parent.classList.contains('mermaid-zoom-wrap')) return;

    var wrap = document.createElement('div');
    wrap.className = 'mermaid-zoom-wrap';
    parent.insertBefore(wrap, svg);
    wrap.appendChild(svg);

    var btn = document.createElement('button');
    btn.className = 'mermaid-zoom-btn';
    btn.title = 'Expand diagram';
    btn.setAttribute('aria-label', 'Expand diagram');
    btn.innerHTML = '&#x26F6;'; /* ⛶ full-screen icon */
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      showModal(svg);
    });
    wrap.appendChild(btn);
  }

  /* ── Modal ─────────────────────────────────────────────────────── */
  function showModal(sourceSvg) {
    var clone = sourceSvg.cloneNode(true);
    /* Let the SVG scale to fill the modal */
    clone.removeAttribute('width');
    clone.removeAttribute('height');
    clone.style.width = 'auto';
    clone.style.height = 'auto';
    clone.style.maxWidth = '100%';
    clone.style.maxHeight = '80vh';

    var inner = document.createElement('div');
    inner.className = 'mermaid-modal-inner';
    inner.addEventListener('click', function (e) { e.stopPropagation(); });

    var closeBtn = document.createElement('button');
    closeBtn.className = 'mermaid-modal-close';
    closeBtn.title = 'Close (Esc)';
    closeBtn.setAttribute('aria-label', 'Close');
    closeBtn.innerHTML = '&times;';

    inner.appendChild(closeBtn);
    inner.appendChild(clone);

    var overlay = document.createElement('div');
    overlay.className = 'mermaid-modal-overlay';
    overlay.appendChild(inner);
    document.body.appendChild(overlay);

    function close() {
      overlay.remove();
      document.removeEventListener('keydown', onKey);
    }
    function onKey(e) { if (e.key === 'Escape') close(); }

    overlay.addEventListener('click', close);
    closeBtn.addEventListener('click', close);
    document.addEventListener('keydown', onKey);

    /* Focus modal so keyboard events work immediately */
    inner.setAttribute('tabindex', '-1');
    inner.focus();
  }

  /* ── Scan and attach ───────────────────────────────────────────── */
  function attachAll() {
    document.querySelectorAll('.mermaid svg').forEach(wrapSvg);
  }

  /* ── Wait for Mermaid to finish rendering via MutationObserver ─── */
  var observer = new MutationObserver(function (mutations) {
    var changed = false;
    mutations.forEach(function (m) {
      m.addedNodes.forEach(function (n) {
        if (n.nodeType === 1) { /* Element */
          if (n.tagName === 'svg' || n.querySelector('svg')) changed = true;
        }
      });
    });
    if (changed) attachAll();
  });

  document.addEventListener('DOMContentLoaded', function () {
    observer.observe(document.body, { childList: true, subtree: true });
    /* Also fire after short delays in case Mermaid already ran */
    setTimeout(attachAll, 200);
    setTimeout(attachAll, 1200);
  });
})();
