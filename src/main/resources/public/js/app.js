(function(){
  const feed = document.getElementById('feed');
  const form = document.getElementById('postForm');
  const inputMsg = document.getElementById('message');
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

  // Enviar mensagem
  form.addEventListener('submit', async function(e){
    e.preventDefault();
    const text = inputMsg.value.trim();
    if(!text){ inputMsg.focus(); return; }
    inputMsg.value = '';

    try {
      await sendToServer(text);
      await pollServer();
    } catch {
      pushSystem('Falha ao enviar ao servidor.');
    }
  });

  // ==== Backend calls ====
  async function sendToServer(text){
    const res = await fetch(API + '/api/pmap', {
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ v: text })
    });
    if(!res.ok) throw new Error('send failed');
  }

  async function loadFromServer(){
    const res = await fetch(API + '/api/pmap', { cache:'no-store' });
    if(!res.ok) return;
    const data = await res.json();
    const items = data.items || {};
    const keys = Object.keys(items).sort(); // ISO ordena por string
    for(const k of keys) renderServerItem(k, items[k]);
    if(keys.length) lastSeen = keys[keys.length - 1];

    pushSystem('Bem-vindo(a)! Digite sua mensagem e publique. Mensagens de grupos autorizados aparecerão aqui.');
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

  function renderServerItem(idIso, text){
    if(SEEN.has(idIso)) return;
    SEEN.add(idIso);
    const ts = Date.parse(idIso) || Date.now();
    addMsg({ type:'group', text:String(text), ts, group:{name:'Feed', verified:false} });
    if(!paused){
      const chatWrap = document.getElementById('chatWrap');
      chatWrap.scrollTop = 0;
    }
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
      badge = '<span class="badge badge-verified ms-2">grupo autorizado</span>' +
              '<button class="badge badge-danger ms-2" data-action="report" data-id="' + id + '" data-text="' + escapeHtml(text) + '">denunciar</button>';
    }

    wrap.innerHTML =
      '<div class="meta mb-1">' + header + ' ' + badge + '</div>' +
      '<div class="bubble">' + linkify(escapeHtml(text)) + '</div>';

    // NOVAS NO TOPO
    if(feed.firstChild) feed.insertBefore(wrap, feed.firstChild);
    else feed.appendChild(wrap);

    // auto-scroll para o topo
    const chatWrap = document.getElementById('chatWrap');
    chatWrap.scrollTo({ top: 0, behavior: 'smooth' });
  }

  // Report (delegação de clique)
  feed.addEventListener('click', function(e){
    const a = e.target.closest('[data-action="report"]');
    if(!a) return;
    const id = a.getAttribute('data-id');
    const txt = a.getAttribute('data-text') || '';
    alert('Denúncia registrada (MVP)\nID: ' + id + '\n\n' + txt);
  });

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
