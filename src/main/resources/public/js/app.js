(function(){
  const feed = document.getElementById('feed');
  const btnTheme = document.getElementById('btnTheme');

  // === BACKEND ===
  const API = "";          // mesma origem (Javalin serve o HTML)
  let lastSeen = null;     // maior ID (ISO) já visto
  const SEEN = new Set();  // evita duplicar render

  // Estado
  let paused = false;
  let msgCounter = 0;

  // Init
  initTheme();
  loadFromServer();
  setInterval(()=>{ if(!paused) pollServer(); }, 4000);

  async function loadFromServer(){
    const res = await fetch(API + '/api/pmap', { cache:'no-store' });
    if(!res.ok) return;
    const data = await res.json();
    const items = data.items || {};
    const keys = Object.keys(items).sort(); // ISO ordena por string
    for(const k of keys) renderServerItem(k, items[k]);
    if(keys.length) lastSeen = keys[keys.length - 1];

    pushSystem('Bem-vindo(a)! Mensagens enviadas para esse<a class="btn btn-sm btn-success ms-2" href="https://wa.me/556182068231" target="_blank" rel="noopener noreferrer">número de WhatsApp</a> aparecem aqui. Adicione no seu grupo de milhas e todas as mensagens virão pra cá!');
  }

  async function pollServer(){
    const res = await fetch(API + '/api/pmap', { cache:'no-store' });
    if(!res.ok) return;
    const {items = {}} = await res.json();
    const keys = Object.keys(items).filter(k => !lastSeen || k > lastSeen).sort();
    for(const k of keys){
      renderServerItem(k, items[k]);
      lastSeen = k;
    }
  }

  function firstName(raw){
    if(!raw) return '';
    const s = String(raw).trim();
    // pega a sequência inicial de letras (inclui acentos), hífen e apóstrofo
    const m = s.match(/^[\p{L}\p{M}\-']+/u);
    return m ? m[0] : s.split(/\s+/u)[0];
  }  

  function renderServerItem(idIso, vStr){
    if (SEEN.has(idIso)) return;
    SEEN.add(idIso);

    // tenta parsear JSON salvo em 'v'
    let data = { text: String(vStr) };
    try {
      const p = JSON.parse(vStr);
      if (p && typeof p === 'object') data = p;
    } catch(_) {}

    const ts = Date.parse(idIso) || Date.now();
    const name = data.name ? String(data.name) : null;
    const wa   = data.wa ? String(data.wa) : null;

    // Botão WhatsApp (se tiver número)
    let actions = '';
    if (wa) {
      const message = `Olá, vi sua mensagem no Mercado Milhas: "${(data.text||'').slice(0,140)}"`;
      const href = `https://wa.me/${wa}?text=${encodeURIComponent(message)}`;
      actions = `<a class="btn btn-sm btn-success ms-2" href="${href}" target="_blank" rel="noopener noreferrer">WhatsApp</a>`;
    }

    // Cabeçalho (inclui nome se tiver)
    const who = name ? escapeHtml(name) : 'Contato';
    const header = `${firstName(who)} · ${new Date(ts).toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'})}`;

    const wrap = document.createElement('div');
    wrap.className = 'msg my-2 msg-group';
    wrap.dataset.id = idIso;
    wrap.innerHTML = `
      <div class="d-flex justify-content-between align-items-center mb-1 meta">
        <div>${header}</div>
        <div>${actions}</div>
      </div>
      <div class="bubble border">${linkify(escapeHtml(data.text || String(vStr)))}</div>
    `;

    // insere no TOPO (se sua UI usa topo)
    const feed = document.getElementById('feed');
    if(feed.firstChild) feed.insertBefore(wrap, feed.firstChild);
    else feed.appendChild(wrap);

    // auto-scroll para o topo (se usa topo)
    document.getElementById('chatWrap').scrollTop = 0;
  }


  // ==== UI helpers ====
  function pushUser(text){ addMsg({type:'user', text, ts: Date.now()}); }
  function pushSystem(text){ addMsg({type:'system', text, ts: Date.now()}); }

  // NOVO: mensagens novas no TOPO
  function addMsg(opts){
    const {type, text, ts, group} = opts;
    const id = 'm' + (++msgCounter) + '_' + ts;
    const time = new Date(ts).toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
    const wrap = document.createElement('div');
    wrap.className = 'msg my-2 msg-' + type;
    wrap.dataset.id = id;

    let header = (type==='user') ? 'Você · ' + time
               : (type==='group') ? (group?.name ? escapeHtml(group.name)+' · ' : '') + time
               : 'Sistema · ' + time;

    let badge = '';
    if(type==='group' && group?.verified){
      badge = '<span class="badge badge-verified ms-2">grupo autorizado</span>';
    }

    wrap.innerHTML =
      '<div class="meta mb-1">' + header + ' ' + badge + '</div>' +
      '<div class="bubble">' + (type=='system'?text:linkify(escapeHtml(text))) + '</div>';

    // NOVAS NO TOPO
    if(feed.firstChild) feed.insertBefore(wrap, feed.firstChild);
    else feed.appendChild(wrap);

    // auto-scroll para o topo
    const chatWrap = document.getElementById('chatWrap');
    chatWrap.scrollTo({ top: 0, behavior: 'smooth' });
  }

  // Theme
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

  // Utils
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
