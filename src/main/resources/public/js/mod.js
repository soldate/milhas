(function(){
  const feed = document.getElementById('feed');
  const btnTheme = document.getElementById('btnTheme');
  const btnRefresh = document.getElementById('btnRefresh');

  const API = "";               // mesma origem
  let lastSeen = null;
  const SEEN = new Set();
  let paused = false;

  initTheme();
  loadFromServer();
  setInterval(()=>{ if(!paused) pollServer(); }, 4000);
  btnRefresh.addEventListener('click', ()=> loadFromServer(true));

  // ==== carregar e renderizar ====
  async function loadFromServer(force){
    const res = await fetch(API + '/api/pmap', { cache:'no-store' });
    if(!res.ok) return;
    const {items={}} = await res.json();

    // limpa se for refresh manual (para reordenar tudo)
    if(force){ feed.innerHTML = ''; SEEN.clear(); lastSeen = null; }

    // ordem: mais novos em cima (IDs são ISO-8601)
    const keys = Object.keys(items).sort();
    for(const k of keys){
      renderItem(k, items[k]);
      if(!lastSeen || k > lastSeen) lastSeen = k;
    }
    scrollTop();
  }

  async function pollServer(){
    const res = await fetch(API + '/api/pmap', { cache:'no-store' });
    if(!res.ok) return;
    const {items={}} = await res.json();
    const keys = Object.keys(items).filter(k => !lastSeen || k > lastSeen).sort().reverse();
    for(const k of keys){
      renderItem(k, items[k]);
      lastSeen = k;
    }
    if(keys.length) scrollTop();
  }

  function renderItem(idIso, text){
    if(SEEN.has(idIso)) return;
    SEEN.add(idIso);

    const ts = Date.parse(idIso) || Date.now();
    const time = new Date(ts).toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});

    const wrap = document.createElement('div');
    wrap.className = 'msg my-2 msg-group';
    wrap.dataset.key = idIso;

    // header com ID e botão excluir
    wrap.innerHTML = `
      <div class="d-flex justify-content-between align-items-center mb-1 meta">
        <div>
          <span>${time}</span>
          <span class="badge badge-verified ms-2">grupo autorizado</span>
          <span class="id-chip ms-2">ID: ${escapeHtml(idIso)}</span>
        </div>
        <div>
          <button class="btn btn-sm btn-outline-danger btn-del" data-action="delete" data-id="${escapeHtml(idIso)}">Excluir</button>
        </div>
      </div>
      <div class="bubble border">${linkify(escapeHtml(String(text)))}</div>
    `;

    // insere no TOPO
    if(feed.firstChild) feed.insertBefore(wrap, feed.firstChild);
    else feed.appendChild(wrap);
  }

  // click delegação para excluir
  feed.addEventListener('click', async (e)=>{
    const btn = e.target.closest('[data-action="delete"]');
    if(!btn) return;
    const id = btn.getAttribute('data-id');
    // if(!confirm('Excluir esta mensagem?\n\nID:\n' + id)) return;

    try{
      const res = await fetch(API + '/api/pmap/' + encodeURIComponent(id), { method:'DELETE' });
      if(!res.ok) throw new Error('delete failed');
      // remove do DOM e do cache
      const el = feed.querySelector(`[data-key="${CSS.escape(id)}"]`);
      if(el) el.remove();
      SEEN.delete(id);
    }catch(err){
      alert('Falha ao excluir. Tente novamente.');
    }
  });

  // ===== helpers =====
  function scrollTop(){ document.getElementById('chatWrap').scrollTop = 0; }

  function initTheme(){
    const saved = localStorage.getItem('mm_theme');
    const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    const theme = saved || (prefersDark ? 'dark' : 'light');
    setTheme(theme);
    btnTheme.addEventListener('click', () => {
      setTheme(document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark');
    });
  }
  function setTheme(theme){
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('mm_theme', theme);
    btnTheme.textContent = theme === 'dark' ? '☀️' : '🌙';
  }
  function escapeHtml(str){
    return String(str)
      .replaceAll('&','&amp;')
      .replaceAll('<','&lt;')
      .replaceAll('>','&gt;')
      .replaceAll('"','&quot;')
      .replaceAll("'",'&#039;');
  }
  function linkify(text){
    const urlRegex = /(https?:\/\/[^\s]+)/g;
    return text.replace(urlRegex, (url) => {
      const clean = url.replace(/"/g,'');
      return `<a href="${clean}" class="link-primary" target="_blank" rel="noopener noreferrer">${clean}</a>`;
    });
  }
})();
