/* AgentScope read-only adapter. Independently implemented against documented mechanisms in
 * browser-use/jev-ultrafast@1231850a0bf1a0c0341fe408ef1668dbbfdfac46 (MIT).
 * This closure is retained as a JSHandle, never installed in the page global namespace. */
allowedUrls => {
  const allowed = new Set(allowedUrls), ids = new WeakMap(), nodes = new Map();
  let next = 0;
  const visible = e => {
    if (!e || !e.isConnected || e.closest('[hidden],[inert],[aria-hidden="true"],script,style,noscript')) return false;
    if (e.checkVisibility && !e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})) return false;
    const css = getComputedStyle(e), r = e.getBoundingClientRect();
    return css.display !== 'none' && css.visibility === 'visible' && Number(css.opacity) !== 0 && r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  };
  const identity = e => { if (!ids.has(e)) ids.set(e, `n${++next}`); const id = ids.get(e); nodes.set(id,e); return id; };
  const text = () => {
    const walker = document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT); let node, result='';
    while ((node=walker.nextNode()) && result.length < 6000) {
      if (!visible(node.parentElement) || !node.textContent.trim()) continue;
      const range=document.createRange(); range.selectNodeContents(node); const r=range.getBoundingClientRect();
      if(r.bottom>0 && r.top<innerHeight && r.right>0 && r.left<innerWidth) result+=node.textContent.trim()+'\n';
    }
    return result.slice(0,6000);
  };
  const snapshot = () => {
    const all=[...document.querySelectorAll('a[href]')].filter(e => visible(e) && !e.hasAttribute('download') && (!e.target || e.target==='_self') && !e.hasAttribute('onclick') && !e.closest('[aria-disabled="true"]') && !e.closest('form') && allowed.has(e.href.split('#')[0]) && (e.innerText.trim() || e.getAttribute('aria-label')));
    const links=all.slice(0,128).map(e=>({id:identity(e),label:(e.getAttribute('aria-label')||e.innerText).trim().slice(0,1000),href:e.href}));
    const result={url:location.href,title:document.title.slice(0,1000),text:text(),links,up:scrollY>0,down:scrollY+innerHeight<document.documentElement.scrollHeight-2,omitted:Math.max(0,all.length-128)};
    // Safe control properties affect freshness but never enter model-visible state.
    const forms=[...document.querySelectorAll('input,select,textarea')].filter(e=>visible(e)&&!['password','hidden','file'].includes(e.type)).map(e=>[identity(e),e.value,e.checked,e.disabled,e.readOnly]);
    result.marker=JSON.stringify([performance.timeOrigin,result,scrollX,scrollY,innerWidth,innerHeight,forms]);
    return result;
  };
  const execute = action => {
    const current=snapshot();
    if(current.marker!==action.marker)return 'STALE';
    if(action.operation==='CLICK'){
      const link=current.links.find(l=>l.id===action.target), node=nodes.get(action.target);
      if(!link||!visible(node)||node.href!==link.href)return 'STALE';
      const r=node.getBoundingClientRect(), x=Math.max(0,Math.min(innerWidth-1,(Math.max(0,r.left)+Math.min(innerWidth,r.right))/2)), y=Math.max(0,Math.min(innerHeight-1,(Math.max(0,r.top)+Math.min(innerHeight,r.bottom))/2));
      const hit=document.elementFromPoint(x,y);if(!hit || (hit!==node&&!node.contains(hit)))return 'REJECTED';
      node.click();return 'APPLIED';
    }
    if(action.operation==='SCROLL_UP'&&current.up){window.scrollBy(0,-Math.floor(innerHeight*.75));return 'APPLIED';}
    if(action.operation==='SCROLL_DOWN'&&current.down){window.scrollBy(0,Math.floor(innerHeight*.75));return 'APPLIED';}
    if(action.operation==='WAIT')return 'APPLIED';
    return 'REJECTED';
  };
  return {snapshot,execute};
}
