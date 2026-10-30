// צופר חירום - האתר. אותם נתונים כמו באפליקציה: התרעות חיות מהשרת, מפה, התרעות אחרונות ומצב המקורות.
'use strict';
const $ = id => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (_) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (_) {} }
};
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const pad = n => String(n).padStart(2, '0');
const hm = d => pad(d.getHours()) + ':' + pad(d.getMinutes());
const dmy = d => pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear();

// ---- כותרות: הסתיים / מקדימה / התרעה (אותם כללים כמו באפליקציה, מהשרת) ----
let CFG = { end: ['הסתיים', 'הוסר', 'ניתן לצאת'], pre: ['בדקות הקרובות', 'צפויות', 'מקדימה', 'איום', 'התקרבו'], endDisplay: 'האירוע הסתיים', prePrefix: null };
const level = t => CFG.end.some(w => t.includes(w)) ? 'end' : CFG.pre.some(w => t.includes(w)) ? 'pre' : 'alert';
function showTitle(t) {
  const l = level(t);
  if (l === 'end') return CFG.endDisplay || t;
  const p = CFG.prePrefix;
  if (l === 'pre' && p && p.contains.some(w => t.includes(w)) && !t.startsWith(p.prefix)) return p.prefix + t;
  return t;
}
const COLOR = { alert: '#D50000', pre: '#F08C00', end: '#2E7D32' };
const ICON = { alert: '🚀', pre: '⚠️', end: '✓' };
const KEEP = { alert: 10 * 60e3, pre: 20 * 60e3, end: 3 * 60e3 };   // כמה זמן אזור נשאר צבוע על המפה

let MIGUN = {};    // יישוב -> שניות להגיע למרחב מוגן
let mine = store.get('mine', []);
let soundOn = false;
const active = new Map();   // אזור -> { lvl, title, at, src }

// ---- מפה ----
const map = L.map('map', { zoomControl: true, attributionControl: false, preferCanvas: true, minZoom: 7, maxZoom: 18 }).setView([31.6, 34.9], 8);
try { maplibregl.setRTLTextPlugin('https://cdn.jsdelivr.net/npm/@mapbox/mapbox-gl-rtl-text@0.3.0/dist/mapbox-gl-rtl-text.js', false); } catch (_) {}
try { L.maplibreGL({ style: mapStyle(true), interactive: false }).addTo(map); } catch (_) {}
const alertLayer = L.layerGroup().addTo(map);
const mineLayer = L.layerGroup().addTo(map);
const showLayer = L.layerGroup().addTo(map);   // התרעה שנבחרה מהרשימה

function shape(name, style) {
  const a = ALL_AREAS[name]; if (!a) return null;
  return a.p ? L.polygon(a.p, style) : L.circleMarker(a.c, Object.assign({ radius: 7 }, style));
}
function drawMine() {
  mineLayer.clearLayers();
  for (const n of mine) { const s = shape(n, { color: '#4C7DFF', weight: 2, fillOpacity: 0.08, dashArray: '4 4', interactive: false }); if (s) s.addTo(mineLayer); }
}
function drawAlerts() {
  alertLayer.clearLayers();
  for (const [n, a] of active) {
    const s = shape(n, { color: COLOR[a.lvl], weight: 1, fillColor: COLOR[a.lvl], fillOpacity: 0.45 });
    if (s) s.bindTooltip(`<b>${esc(n)}</b><br>${esc(showTitle(a.title))} · ${hm(new Date(a.at))}`, { sticky: true }).addTo(alertLayer);
  }
}
function fitTo(names) {
  const pts = names.map(n => ALL_AREAS[n] && ALL_AREAS[n].c).filter(Boolean);
  if (pts.length) map.fitBounds(L.latLngBounds(pts).pad(0.3), { maxZoom: 12 });
}

// ---- האזורים שלי ----
function renderMine() {
  $('chips').innerHTML = mine.length ? mine.map((n, i) => `<span class="chip">${esc(n)}<button data-i="${i}" title="הסר">✕</button></span>`).join('')
    : '<span class="chip" style="color:var(--muted)">כל הארץ</span>';
  drawMine(); renderState(); renderList();
}
$('chips').onclick = e => { const i = e.target.dataset.i; if (i == null) return; mine.splice(+i, 1); store.set('mine', mine); renderMine(); };
$('addForm').onsubmit = e => {
  e.preventDefault();
  const v = $('addIn').value.trim();
  if (!ALL_AREAS[v] && !MIGUN[v]) { $('addIn').style.borderColor = 'var(--red)'; return; }
  $('addIn').style.borderColor = '';
  if (!mine.includes(v)) { mine.push(v); store.set('mine', mine); }
  $('addIn').value = ''; renderMine(); fitTo(mine);
};
$('areaList').innerHTML = Object.keys(ALL_AREAS).sort((a, b) => a.localeCompare(b, 'he')).map(n => `<option value="${esc(n)}">`).join('');
const isMine = areas => !mine.length || areas.some(a => mine.includes(a));

// ---- כרטיס מצב: מוגן / התרעה ----
function myActive() {
  const list = [...active].filter(([n]) => !mine.length || mine.includes(n));
  if (!list.length) return null;
  const rank = { alert: 3, pre: 2, end: 1 };
  list.sort((a, b) => rank[b[1].lvl] - rank[a[1].lvl] || b[1].at - a[1].at);
  const top = list[0][1];
  return { top, areas: list.filter(([, a]) => a.title === top.title).map(([n]) => n) };
}
function secsLeft(m) {
  if (m.top.lvl !== 'alert') return null;
  const s = m.areas.map(n => MIGUN[n]).filter(x => x != null);
  if (!s.length) return null;
  return Math.max(0, Math.ceil((m.top.at + Math.min(...s) * 1000 - Date.now()) / 1000));
}
const fmtSec = s => s >= 60 ? Math.floor(s / 60) + ':' + pad(s % 60) : String(s);
function renderState() {
  const el = $('state'), m = myActive();
  if (!m) {
    el.className = 'card state';
    el.innerHTML = `<div class="shield">🛡️</div><div class="t1">מוגן</div><div class="t2">${mine.length ? 'אין התרעות באזורים שלך' : 'אין התרעות כרגע'}</div>`;
    return;
  }
  const left = secsLeft(m), more = m.areas.length > 6 ? ` ועוד ${m.areas.length - 6}` : '';
  el.className = 'card state alert'; el.style.background = COLOR[m.top.lvl];
  el.innerHTML = `<div class="t1">${esc(showTitle(m.top.title))}</div>
    <div class="areas">${esc(m.areas.slice(0, 6).join(', '))}${more}</div>
    ${left != null ? `<div class="timer">${fmtSec(left)}</div><div class="t2">${left ? 'להיכנס למרחב מוגן' : 'הישארו במרחב המוגן'}</div>` : ''}
    <div class="src">${hm(new Date(m.top.at))}${m.top.src ? ' · ' + esc(srcName(m.top.src)) : ''}</div>`;
}
// באנר על כל המסך כשיש התרעה באזור שלי
let bannerFor = '';
function showBanner(alert, areas) {
  const lvl = level(alert.title);
  $('bannerBox').style.background = COLOR[lvl];
  $('bT1').textContent = showTitle(alert.title);
  $('bT2').textContent = lvl === 'alert' ? 'היכנסו למרחב המוגן' : lvl === 'pre' ? 'התקרבו למרחב מוגן' : 'ניתן לצאת מהמרחב המוגן';
  $('bAreas').textContent = areas.slice(0, 8).join(', ') + (areas.length > 8 ? ` ועוד ${areas.length - 8}` : '');
  bannerFor = alert.title + '|' + alert.at;
  $('banner').classList.add('show');
  tickBanner();
}
function tickBanner() {
  if (!$('banner').classList.contains('show')) return;
  const m = myActive(), left = m && secsLeft(m);
  $('bTimer').textContent = left != null ? fmtSec(left) : '';
}
$('bClose').onclick = () => { $('banner').classList.remove('show'); stopSiren(); };

// ---- צליל והתראות במחשב ----
const siren = $('siren');
function setSound(on) {
  soundOn = on; store.set('sound', on);
  $('soundBtn').textContent = on ? '🔊' : '🔇'; $('soundBtn').classList.toggle('on', on);
}
$('soundBtn').onclick = () => {
  if (soundOn) return setSound(false);
  // לחיצה של המשתמש "פותחת" את הצליל בדפדפן - מנגנים שקט לרגע
  siren.muted = true;
  siren.play().then(() => { siren.pause(); siren.currentTime = 0; siren.muted = false; setSound(true); }).catch(() => setSound(false));
};
function playSiren() { if (!soundOn) return; siren.currentTime = 0; siren.play().catch(() => {}); }
function stopSiren() { siren.pause(); siren.currentTime = 0; }
function renderNotif() {
  const ok = 'Notification' in window && Notification.permission === 'granted';
  $('notifBtn').textContent = ok ? '🔔' : '🔕'; $('notifBtn').classList.toggle('on', ok);
}
$('notifBtn').onclick = () => { if ('Notification' in window) Notification.requestPermission().then(renderNotif); };
function notify(alert, areas) {
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  const opt = { body: areas.join(', '), icon: '/icon.png', badge: '/icon.png', tag: alert.title, requireInteraction: level(alert.title) === 'alert' };
  // דרך ה-Service Worker (בטלפון אנדרואיד זו הדרך היחידה); אם אין - ישירות
  if (swReg) { swReg.showNotification(showTitle(alert.title), opt).catch(() => {}); return; }
  try { const n = new Notification(showTitle(alert.title), opt); n.onclick = () => { window.focus(); n.close(); }; } catch (_) {}
}
// התקנה כאפליקציה (PWA)
let swReg = null;
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').then(r => { swReg = r; }).catch(() => {});

// ---- התרעות חיות ----
const seen = new Set();   // התרעות שכבר הוצגו - שחיבור מחדש לא יפעיל אותן שוב
function onAlert(a, replay) {
  const key = a.title + '|' + a.at + '|' + a.areas.join(',');
  if (seen.has(key)) return;
  seen.add(key);
  const lvl = level(a.title);
  for (const n of a.areas) {
    // "הסתיים" מחליף התרעה קיימת; התרעה לא נדרסת ע"י מקדימה
    const cur = active.get(n);
    if (cur && cur.lvl === 'alert' && lvl === 'pre' && a.at - cur.at < KEEP.alert) continue;
    active.set(n, { lvl, title: a.title, at: a.at, src: a.src });
  }
  drawAlerts(); renderState();
  addRecent({ t: a.title, b: a.areas.join(', '), ts: a.at });
  if (replay) return;
  const my = mine.length ? a.areas.filter(n => mine.includes(n)) : a.areas;
  if (!my.length) return;
  notify(a, my);
  if (lvl === 'end') { stopSiren(); if ($('banner').classList.contains('show')) showBanner(a, my); return; }
  showBanner(a, my);
  if (lvl === 'alert') playSiren();
}
function expire() {
  const now = Date.now(); let ch = false;
  for (const [n, a] of active) if (now - a.at > KEEP[a.lvl]) { active.delete(n); ch = true; }
  if (ch) { drawAlerts(); renderState(); if (!myActive()) $('banner').classList.remove('show'); }
}
let es, firstConn = true;
function connect() {
  es = new EventSource('/api/live');
  // בכניסה לאתר השרת שולח קודם את חצי השעה האחרונה ואז "ready" - אלה מוצגות בשקט (בלי צופר ובלי מסך אדום).
  // בחיבור מחדש - מה שלא ראינו (הגיע בזמן הניתוק) מופעל כרגיל, ומה שכבר ראינו מדולג
  let quiet = firstConn;
  es.onopen = () => { $('live').className = 'live on'; $('liveTx').textContent = 'מחובר'; };
  es.addEventListener('ready', () => { quiet = false; firstConn = false; });
  es.addEventListener('alert', e => { try { onAlert(JSON.parse(e.data), quiet); } catch (_) {} });
  es.onerror = () => {
    $('live').className = 'live off'; $('liveTx').textContent = 'מתחבר מחדש…';
    if (es.readyState === 2) { setTimeout(() => fetch('/api/state').then(r => { if (r.status === 401) location.reload(); else connect(); }).catch(() => setTimeout(connect, 5000)), 3000); }
  };
}

// ---- התרעות אחרונות ----
let recent = [], mode = 'all', shown = 60;
function addRecent(e) {
  if (recent.some(x => x.t === e.t && Math.abs(x.ts - e.ts) < 60e3 && x.b === e.b)) return;
  recent.unshift(e); recent.sort((a, b) => b.ts - a.ts); renderList();
}
function renderList() {
  const list = recent.filter(e => mode === 'all' || isMine(e.b.split(', ')));
  if (!list.length) { $('list').innerHTML = `<div class="day">${mode === 'mine' ? 'אין התרעות באזורים שלך' : 'אין התרעות'}</div>`; return; }
  let html = '', day = '';
  list.slice(0, shown).forEach((e, i) => {
    const d = new Date(e.ts), dd = dmy(d), lvl = level(e.t), areas = e.b.split(', ');
    if (dd !== day) { day = dd; html += `<div class="day">${dd === dmy(new Date()) ? 'היום' : dd}</div>`; }
    html += `<div class="item" data-i="${recent.indexOf(e)}"><div class="ic" style="background:${COLOR[lvl]}">${ICON[lvl]}</div>
      <div class="tx"><div class="a">${esc(showTitle(e.t))}</div><div class="b">${esc(areas.length > 4 ? areas.slice(0, 4).join(', ') + ` ועוד ${areas.length - 4}` : e.b)}</div></div>
      <div class="tm">${hm(d)}</div></div>`;
  });
  if (list.length > shown) html += '<button class="more" id="more">עוד</button>';
  $('list').innerHTML = html;
  const mb = $('more'); if (mb) mb.onclick = () => { shown += 60; renderList(); };
}
$('list').onclick = e => {
  const it = e.target.closest('.item'); if (!it) return;
  const ev = recent[+it.dataset.i], lvl = level(ev.t), names = ev.b.split(', ');
  showLayer.clearLayers();
  for (const n of names) { const s = shape(n, { color: COLOR[lvl], weight: 2, fillColor: COLOR[lvl], fillOpacity: 0.3, dashArray: '2 3' }); if (s) s.bindTooltip(esc(n), { sticky: true }).addTo(showLayer); }
  fitTo(names);
  clearTimeout(showLayer.t); showLayer.t = setTimeout(() => showLayer.clearLayers(), 60e3);
};
$('seg').onclick = e => {
  const m = e.target.dataset.m; if (!m) return;
  mode = m; shown = 60;
  for (const b of $('seg').children) b.classList.toggle('sel', b.dataset.m === m);
  renderList();
};
function loadArchive() {
  fetch('/archive?since=' + (Date.now() - 7 * 24 * 3600e3)).then(r => r.json()).then(list => {
    for (const e of list) if (!recent.some(x => x.t === e.t && Math.abs(x.ts - e.ts) < 60e3)) recent.push(e);
    recent.sort((a, b) => b.ts - a.ts); renderList();
  }).catch(() => {});
}

// ---- מצב השרת והמקורות ----
const SOURCES = [['oref', 'פיקוד העורף – חי'], ['history', 'פיקוד העורף – היסטוריה'], ['archive', 'פיקוד העורף – ארכיון'], ['tzofar', 'צופר'],
  ['tg:PikudHaOref_all', 'טלגרם – פיקוד העורף'], ['tg:tzevaadomm', 'טלגרם – צופר'], ['tg:CumtaAlertsChannel', 'טלגרם – כומתה'], ['tg:Radar_Alerts', 'טלגרם – רדאר']];
const srcName = k => (SOURCES.find(x => x[0] === k) || [k, k])[1];
function upFor(ms) {
  const m = Math.floor(ms / 60e3), h = Math.floor(m / 60), d = Math.floor(h / 24);
  return d ? `${d} ימים` : h ? `${h} שעות` : `${m} דקות`;
}
function loadState() {
  fetch('/api/state').then(r => { if (r.status === 401) location.reload(); return r.json(); }).then(s => {
    const up = new Set(s.up);
    const n = SOURCES.filter(([k]) => up.has(k)).length;
    $('srvTx').innerHTML = `<span style="color:${n === SOURCES.length ? 'var(--green)' : n ? 'var(--orange)' : 'var(--red)'}">${n}/${SOURCES.length} מקורות</span> · פועל ${upFor(s.now - s.started)}`;
    $('sources').innerHTML = SOURCES.map(([k, name]) => `<div class="row"><span class="src-dot" style="background:${up.has(k) ? 'var(--green)' : 'var(--red)'}"></span>${name}<span class="end">${up.has(k) ? 'עובד' : 'לא עונה'}</span></div>`).join('')
      + `<div class="row" style="color:var(--muted);font-size:13px">גרסה אחרונה: ${esc(s.version || '–')} · נתונים: ${esc(s.data || '–')}</div>`;
  }).catch(() => { $('srvTx').innerHTML = '<span style="color:var(--red)">השרת לא עונה</span>'; });
}

// ---- מכשירים וקריסות ----
const ago = t => { const m = Math.floor((Date.now() - t) / 60e3); return m < 1 ? 'עכשיו' : m < 60 ? `לפני ${m} דק'` : m < 1440 ? `לפני ${Math.floor(m / 60)} שע'` : `לפני ${Math.floor(m / 1440)} ימים`; };
function loadDevices() {
  fetch('/api/devices').then(r => r.json()).then(({ devices, crashes, messages = [] }) => {
    // פעיל = דיווח ב-25 הדקות האחרונות (הטלפון מדווח פעם ב-10 דקות)
    const live = d => Date.now() - d.at < 25 * 60e3;
    const n = devices.filter(d => live(d) && d.on).length;
    $('devTx').innerHTML = devices.length ? `<span style="color:${n === devices.length ? 'var(--green)' : 'var(--orange)'}">${n}/${devices.length} מוגנים</span>` : '';
    $('devices').innerHTML = devices.length ? devices.map(d => {
      const ok = live(d) && d.on, color = ok ? 'var(--green)' : live(d) ? 'var(--orange)' : 'var(--red)';
      const st = !live(d) ? 'לא מדווח' : d.on ? 'מוגן' : 'כבוי';
      return `<div class="row"><span class="src-dot" style="background:${color}"></span><div style="min-width:0"><div>${esc(d.m || d.d)}</div>
        <div style="font-size:12px;color:var(--muted)">${esc(d.d)} · גרסה ${esc(d.v)} · ${ago(d.at)}</div></div><span class="end">${st}</span></div>`;
    }).join('') : '<div class="hint" style="padding:12px 18px">עוד אין דיווחים – הטלפונים יופיעו אחרי העדכון הבא של האפליקציה</div>';
    // שליחת הודעה: רשימת הנמענים + ההודעות האחרונות
    const sel = $('msgTo'), cur = sel.value;
    sel.innerHTML = '<option value="all">כל הטלפונים</option>' + devices.map(d => `<option value="${esc(d.d)}">${esc(d.m || d.d)} (${esc(d.d)})</option>`).join('');
    sel.value = [...sel.options].some(o => o.value === cur) ? cur : 'all';
    const toName = t => t === 'all' ? 'כל הטלפונים' : ((devices.find(d => d.d === t) || {}).m || t);
    $('msgs').innerHTML = messages.slice(0, 5).map(m => `<div class="item"><div class="ic" style="background:var(--blue)">✉</div>
      <div class="tx"><div class="a">${esc(m.text)}</div><div class="b">אל: ${esc(toName(m.to))}</div></div><div class="tm">${dmy(new Date(m.at))} ${hm(new Date(m.at))}</div></div>`).join('');
    $('crashCard').style.display = crashes.length ? '' : 'none';
    $('crashTx').textContent = crashes.length ? crashes.length + '' : '';
    $('crashes').innerHTML = crashes.slice(0, 20).map((c, i) => `<div class="item" data-c="${i}"><div class="ic" style="background:var(--red)">!</div>
      <div class="tx"><div class="a">${esc(c.m || c.d)} · גרסה ${esc(c.v)}</div><div class="b" dir="ltr" style="text-align:right">${esc((c.err.split('\n')[0] || ''))}</div>
      <pre style="display:none;white-space:pre;overflow-x:auto;max-width:100%;direction:ltr;text-align:left;font-size:11px;color:var(--muted);margin:8px 0 0">${esc(c.err)}</pre></div>
      <div class="tm">${dmy(new Date(c.at))} ${hm(new Date(c.at))}</div></div>`).join('');
  }).catch(() => {});
}
$('crashes').onclick = e => { const it = e.target.closest('.item'); if (!it) return; const pre = it.querySelector('pre'); pre.style.display = pre.style.display ? '' : 'none'; };

$('msgForm').onsubmit = e => {
  e.preventDefault();
  const text = $('msgText').value.trim(); if (!text) return;
  const to = $('msgTo').value, btn = e.target.querySelector('button');
  btn.disabled = true; $('msgSt').textContent = 'שולח…';
  fetch('/api/message', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text, to }) })
    .then(r => { if (!r.ok) throw 0; $('msgText').value = ''; $('msgSt').textContent = '✓ נשלח'; loadDevices(); })
    .catch(() => { $('msgSt').textContent = 'השליחה נכשלה'; })
    .finally(() => { btn.disabled = false; setTimeout(() => { $('msgSt').textContent = ''; }, 5000); });
};

// ---- שעון ----
function tick() {
  const d = new Date();
  $('clock').textContent = hm(d) + ':' + pad(d.getSeconds());
  expire();
  if (myActive()) { renderState(); tickBanner(); }
}

// ---- הפעלה ----
setSound(false);   // הדפדפן דורש לחיצה כדי לנגן - בכל כניסה לוחצים שוב על הרמקול
renderNotif();
fetch('/data/config.json').then(r => r.json()).then(c => { if (c.titles) CFG = Object.assign(CFG, c.titles); renderList(); renderState(); drawAlerts(); }).catch(() => {});
fetch('/assets/migun.txt').then(r => r.text()).then(t => {
  for (const line of t.split('\n')) { const [n, s] = line.split('\t'); if (n && s) MIGUN[n.trim()] = +s; }
}).catch(() => {});
renderMine();
if (mine.length) fitTo(mine);
loadArchive(); setInterval(loadArchive, 5 * 60e3);
loadState(); setInterval(loadState, 30e3);
loadDevices(); setInterval(loadDevices, 60e3);
connect();
tick(); setInterval(tick, 1000);
