(function () {
  'use strict';

  var EXPAND_ICON =
    '<svg viewBox="0 0 24 24" width="13" height="13" fill="none"' +
    ' stroke="currentColor" stroke-width="2.5"' +
    ' stroke-linecap="round" stroke-linejoin="round">' +
    '<polyline points="15 3 21 3 21 9"/>' +
    '<polyline points="9 21 3 21 3 15"/>' +
    '<line x1="21" y1="3" x2="14" y2="10"/>' +
    '<line x1="3" y1="21" x2="10" y2="14"/>' +
    '</svg>';

  /* ── Styles ──────────────────────────────────────────────────────── */
  var css = [
    /* Hide sphinxcontrib-mermaid's own fullscreen button */
    '.mermaid-fullscreen-btn { display: none !important; }',

    /* Wrapper */
    '.mz-wrap { position: relative; display: block; }',

    /* Expand button – always slightly visible, bright on hover */
    '.mz-btn {',
    '  position: absolute; top: 6px; right: 6px; z-index: 20;',
    '  display: flex; align-items: center; justify-content: center;',
    '  width: 28px; height: 28px;',
    '  background: rgba(30,30,30,0.70); color: #fff;',
    '  border: none; border-radius: 5px; cursor: pointer;',
    '  opacity: 0.50; transition: opacity 0.18s, background 0.18s;',
    '  box-shadow: 0 2px 6px rgba(0,0,0,0.30);',
    '}',
    '.mz-btn:hover { opacity: 1; background: rgba(30,30,30,0.90); }',
    '.mz-wrap:hover .mz-btn { opacity: 0.80; }',

    /* ── Modal overlay ── */
    '.mz-overlay {',
    '  position: fixed; inset: 0; z-index: 99999;',
    '  background: rgba(0,0,0,0.70);',
    '  display: flex; flex-direction: column;',
    '  align-items: center; justify-content: center;',
    '  animation: mz-fade 0.15s ease;',
    '}',
    '@keyframes mz-fade { from{opacity:0} to{opacity:1} }',

    /* Modal box */
    '.mz-box {',
    '  display: flex; flex-direction: column;',
    '  width: 92vw; max-width: 1300px; height: 88vh;',
    '  background: #fff; border-radius: 10px;',
    '  box-shadow: 0 16px 56px rgba(0,0,0,0.40); overflow: hidden;',
    '}',

    /* Close button (top-right of box) */
    '.mz-close {',
    '  position: absolute; top: 0; right: 0; z-index: 2;',
    '  background: #e53935; color: #fff; border: none;',
    '  width: 36px; height: 36px; font-size: 18px; line-height: 1;',
    '  border-bottom-left-radius: 8px; cursor: pointer;',
    '  display: flex; align-items: center; justify-content: center;',
    '}',
    '.mz-close:hover { background: #c62828; }',

    /* Scrollable SVG area */
    '.mz-viewport {',
    '  flex: 1; overflow: auto; padding: 20px;',
    '  cursor: grab; user-select: none;',
    '  background: #f8f8f8;',
    '}',
    '.mz-viewport.mz-grabbing { cursor: grabbing; }',

    /* SVG holder – width % controls zoom */
    '.mz-svgholder { min-width: 100%; display: inline-block; }',
    '.mz-svgholder svg {',
    '  display: block !important;',
    '  width: 100% !important; height: auto !important;',
    '  max-width: none !important;',
    '}',

    /* ── Toolbar ── high-contrast dark strip ── */
    '.mz-toolbar {',
    '  display: flex; align-items: center; justify-content: center; gap: 6px;',
    '  padding: 10px 16px; flex-shrink: 0;',
    '  background: #1e1e2e; border-top: none;',
    '}',

    /* Zoom action buttons */
    '.mz-toolbar .mz-tbtn {',
    '  width: 36px; height: 36px; font-size: 22px; font-weight: 700;',
    '  display: flex; align-items: center; justify-content: center;',
    '  background: #373752; color: #fff;',
    '  border: 1.5px solid #5a5a7a; border-radius: 6px;',
    '  cursor: pointer; transition: background 0.15s;',
    '  line-height: 1;',
    '}',
    '.mz-toolbar .mz-tbtn:hover { background: #5a5a9e; border-color: #8080c0; }',
    '.mz-toolbar .mz-tbtn:disabled { opacity: 0.30; cursor: default; }',

    /* Zoom label */
    '.mz-zlabel {',
    '  min-width: 62px; text-align: center;',
    '  font-size: 14px; font-weight: 600;',
    '  color: #cdd; font-variant-numeric: tabular-nums;',
    '}',

    /* Hint text */
    '.mz-hint {',
    '  font-size: 11px; color: #777; margin-left: 12px;',
    '}',
  ].join('\n');

  var styleEl = document.createElement('style');
  styleEl.textContent = css;
  document.head.appendChild(styleEl);

  /* ── Wrap helper ─────────────────────────────────────────────────── */
  function wrapEl(el) {
    if (el.parentElement && el.parentElement.classList.contains('mz-wrap')) return;

    var wrap = document.createElement('div');
    wrap.className = 'mz-wrap';
    el.parentNode.insertBefore(wrap, el);
    wrap.appendChild(el);

    var btn = document.createElement('button');
    btn.className = 'mz-btn';
    btn.title = 'Expand diagram';
    btn.setAttribute('aria-label', 'Expand diagram');
    btn.innerHTML = EXPAND_ICON;
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      var svg = el.querySelector('svg');
      if (svg) showModal(svg);
    });
    wrap.appendChild(btn);
  }

  /* ── Modal ───────────────────────────────────────────────────────── */
  var MIN_SCALE = 0.2, MAX_SCALE = 8, STEP = 0.25;

  function showModal(sourceSvg) {
    var scale = 1.0;

    /* Clone + strip Mermaid's size constraints */
    var svgClone = sourceSvg.cloneNode(true);
    svgClone.removeAttribute('width');
    svgClone.removeAttribute('height');
    var s = (svgClone.getAttribute('style') || '')
              .replace(/max-width\s*:[^;]+;?/gi, '')
              .replace(/\bwidth\s*:[^;]+;?/gi, '');
    svgClone.setAttribute('style', s);

    /* SVG holder */
    var holder = document.createElement('div');
    holder.className = 'mz-svgholder';
    holder.style.width = '100%';
    holder.appendChild(svgClone);

    /* Viewport */
    var viewport = document.createElement('div');
    viewport.className = 'mz-viewport';
    viewport.appendChild(holder);

    /* Toolbar */
    var zlabel = document.createElement('span');
    zlabel.className = 'mz-zlabel';

    function mkBtn(html, title, cb) {
      var b = document.createElement('button');
      b.className = 'mz-tbtn';
      b.innerHTML = html; b.title = title;
      b.addEventListener('click', cb);
      return b;
    }
    var btnOut   = mkBtn('&minus;', 'Zoom out (−)', function () { setScale(scale - STEP); });
    var btnReset = mkBtn('&#x21BA;', 'Reset zoom', function () { setScale(1.0); });
    var btnIn    = mkBtn('&plus;',  'Zoom in (+)', function () { setScale(scale + STEP); });

    var hint = document.createElement('span');
    hint.className = 'mz-hint';
    hint.textContent = 'scroll · drag to pan';

    function setScale(s) {
      scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.round(s * 100) / 100));
      holder.style.width = Math.round(scale * 100) + '%';
      zlabel.textContent = Math.round(scale * 100) + '%';
      btnOut.disabled = scale <= MIN_SCALE;
      btnIn.disabled  = scale >= MAX_SCALE;
    }

    var toolbar = document.createElement('div');
    toolbar.className = 'mz-toolbar';
    [btnOut, zlabel, btnReset, btnIn, hint].forEach(function (n) { toolbar.appendChild(n); });

    /* Close button */
    var closeBtn = document.createElement('button');
    closeBtn.className = 'mz-close';
    closeBtn.innerHTML = '&times;';
    closeBtn.title = 'Close (Esc)';

    /* Box */
    var box = document.createElement('div');
    box.className = 'mz-box';
    box.style.position = 'relative';
    box.addEventListener('click', function (e) { e.stopPropagation(); });
    box.appendChild(closeBtn);
    box.appendChild(viewport);
    box.appendChild(toolbar);

    /* Overlay */
    var overlay = document.createElement('div');
    overlay.className = 'mz-overlay';
    overlay.appendChild(box);
    document.body.appendChild(overlay);

    setScale(1.0);

    /* Wheel zoom */
    viewport.addEventListener('wheel', function (e) {
      e.preventDefault();
      setScale(scale + (e.deltaY > 0 ? -STEP * 0.5 : STEP * 0.5));
    }, { passive: false });

    /* Drag to pan */
    var drag = null;
    viewport.addEventListener('mousedown', function (e) {
      if (e.button !== 0) return;
      drag = { x: e.clientX, y: e.clientY, sl: viewport.scrollLeft, st: viewport.scrollTop };
      viewport.classList.add('mz-grabbing');
    });
    function onMove(e) {
      if (!drag) return;
      viewport.scrollLeft = drag.sl - (e.clientX - drag.x);
      viewport.scrollTop  = drag.st - (e.clientY - drag.y);
    }
    function onUp() { drag = null; viewport.classList.remove('mz-grabbing'); }
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);

    /* Keyboard */
    function onKey(e) {
      if (e.key === 'Escape')                  close();
      else if (e.key === '+' || e.key === '=') setScale(scale + STEP);
      else if (e.key === '-')                  setScale(scale - STEP);
      else if (e.key === '0')                  setScale(1.0);
    }
    document.addEventListener('keydown', onKey);

    function close() {
      overlay.remove();
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    }
    overlay.addEventListener('click', close);
    closeBtn.addEventListener('click', close);

    box.setAttribute('tabindex', '-1');
    box.focus();
  }

  /* ── Attach: prefer .mermaid-container (added by sphinxcontrib-mermaid) ── */
  function attachAll() {
    /* Wrap .mermaid-container blocks that contain a rendered SVG */
    document.querySelectorAll('.mermaid-container').forEach(function (c) {
      if (c.querySelector('svg')) wrapEl(c);
    });
    /* Fallback: pre.mermaid not yet wrapped inside .mermaid-container */
    document.querySelectorAll('pre.mermaid').forEach(function (pre) {
      if (!pre.closest('.mermaid-container') && pre.querySelector('svg')) wrapEl(pre);
    });
  }

  /* Watch for async rendering */
  var observer = new MutationObserver(function (mutations) {
    var need = false;
    mutations.forEach(function (m) {
      m.addedNodes.forEach(function (n) {
        if (!need && n.nodeType === 1 &&
            (n.tagName.toLowerCase() === 'svg' || (n.querySelector && n.querySelector('svg')))) {
          need = true;
        }
      });
    });
    if (need) attachAll();
  });

  document.addEventListener('DOMContentLoaded', function () {
    observer.observe(document.body, { childList: true, subtree: true });
    var tries = 0;
    var poll = setInterval(function () {
      attachAll();
      if (++tries >= 12) clearInterval(poll);
    }, 500);
  });
})();
