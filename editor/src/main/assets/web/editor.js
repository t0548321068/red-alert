// שכבת העריכה: בחירת מסך, לחיצה על רכיב, חלון עריכה (צבע / גודל / טקסט / מיקום / הערה),
// רשימת השינויים ושליחה אחת לשרת. השינויים נשמרים בטלפון (localStorage) עד השליחה
'use strict';

const $ = id => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (_) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (_) {} }
};

// ---- מצב ----
let dark = store.get('theme', 'dark') === 'dark';
let cur = store.get('screen', 'main');
/** edits[מסך|ערכה][רכיב] = { label, kind, base:{...}, color, size, text, radius, dx, dy, note } */
let edits = store.get('edits', {});
let sel = null, selId = null, before = null, tab = 'color';
const saveEdits = () => store.set('edits', edits);
const skey = () => cur + '|' + (dark ? 'dark' : 'light');
const screenOf = id => SCREENS.find(s => s.id === id) || SCREENS[0];

// ---- תשובות מהמעטפת (Native) ----
const cbs = {}; let cbn = 0;
window.__cb = (id, v) => { const f = cbs[id]; delete cbs[id]; if (f) f(v); };
function nat(name, ...args) {
  return new Promise(res => {
    if (!window.Native) return res('');
    const id = ++cbn; cbs[id] = res; Native[name](...args, id);
  });
}

// ---- צבעים ----
function toHex(c) {
  const m = String(c).match(/rgba?\(([^)]+)\)/);
  if (!m) return c;
  const p = m[1].split(',').map(x => parseFloat(x));
  const h = n => Math.round(n).toString(16).padStart(2, '0').toUpperCase();
  const a = p.length > 3 ? p[3] : 1;
  if (a === 0) return 'שקוף';
  return a < 1 ? '#' + h(a * 255) + h(p[0]) + h(p[1]) + h(p[2]) : '#' + h(p[0]) + h(p[1]) + h(p[2]);
}
const rgb6 = hex => hex && hex[0] === '#' ? '#' + hex.slice(-6) : '#ffffff';

// ---- בניית המסך ----
function render() {
  const s = screenOf(cur);
  applyTheme(dark);
  resetKeys();
  H = $('stage').clientHeight - 24;
  $('screen').innerHTML = s.build();
  $('screen').style.background = css(C.BG);
  const sbBg = s.bar || ($('screen').firstElementChild && getComputedStyle($('screen').firstElementChild).backgroundColor) || css(C.BG);
  $('sb').style.background = sbBg;
  $('sb').style.color = !s.bar && C.LIGHT ? '#000' : '#fff';
  $('sbTime').textContent = nowHM(false);
  $('scrLabel').textContent = s.name + (dark ? '' : ' · בהיר');
  applyAll();
  updCount();
}

/** ערך מקורי של רכיב (לפני שינוי) */
function baseOf(el) {
  const k = el.dataset.k, cs = getComputedStyle(el);
  const b = { };
  if (k === 't') { b.color = toHex(cs.color); b.size = Math.round(parseFloat(cs.fontSize)); b.text = el.innerText; }
  else if (k === 'i') { b.color = toHex(el.style.getPropertyValue('--tint') || '') || 'מקורי'; b.size = Math.round(el.offsetWidth); }
  else { b.color = toHex(cs.backgroundColor); b.radius = Math.round(parseFloat(cs.borderTopLeftRadius) || 0); }
  if (k === 'i' && !el.classList.contains('tinted')) b.color = 'מקורי';
  return b;
}

/** החלת השינויים של רכיב אחד על המסך */
function applyOne(el, e) {
  const k = el.dataset.k;
  if (e.color != null) {
    if (k === 't') el.style.color = e.color;
    else if (k === 'i') { el.classList.add('tinted'); el.style.setProperty('--tint', e.color); }
    else el.style.background = e.color;
  }
  if (e.size != null) {
    if (k === 't') el.style.fontSize = e.size + 'px';
    else if (k === 'i') { el.style.width = e.size + 'px'; el.style.height = e.size + 'px'; }
  }
  if (e.radius != null) el.style.borderRadius = e.radius + 'px';
  if (e.text != null && k === 't') el.innerHTML = esc(e.text).replace(/\n/g, '<br>');
  el.style.transform = (e.dx || e.dy) ? `translate(${e.dx || 0}px,${e.dy || 0}px)` : '';
  el.classList.toggle('chg', hasChange(e));
}
function hasChange(e) {
  return !!(e && (e.color != null || e.size != null || e.radius != null || e.text != null || e.dx || e.dy || (e.note && e.note.trim())));
}
function applyAll() {
  const m = edits[skey()] || {};
  for (const id in m) {
    const el = $('screen').querySelector(`[data-e="${CSS.escape(id)}"]`);
    if (el) applyOne(el, m[id]);
  }
}

// ---- בחירת רכיב ----
$('screen').addEventListener('click', ev => {
  const el = ev.target.closest('[data-e]');
  ev.preventDefault(); ev.stopPropagation();
  if (!el || el === sel) return;
  // רכיב אחר בזמן עריכה: מה שכבר שונה נשמר, ועוברים לרכיב החדש
  if (sel) { const id = el.dataset.e; commit(); render(); const n = findEl(id); if (n) select(n); }
  else select(el);
}, true);
const findEl = id => $('screen').querySelector(`[data-e="${CSS.escape(id)}"]`);
/** שמירת הרכיב הנוכחי (בלי לסגור את החלון) */
function commit() {
  const m = edits[skey()] || {};
  if (selId && m[selId] && !hasChange(m[selId])) delete m[selId];
  else if (selId && m[selId]) m[selId].at = Date.now();
  saveEdits(); updCount();
}

function select(el) {
  if (sel) sel.classList.remove('sel');
  sel = el; selId = el.dataset.e;
  el.classList.add('sel');
  const m = edits[skey()] || (edits[skey()] = {});
  // ערך מקורי - נמדד כשעוד אין שינויים ברכיב
  if (!m[selId]) m[selId] = { label: el.dataset.n, kind: el.dataset.k, base: baseOf(el) };
  before = JSON.parse(JSON.stringify(m[selId]));
  tab = 'color';
  openSheet();
  // שהרכיב לא יוסתר מאחורי החלון
  setTimeout(() => {
    const r = el.getBoundingClientRect(), sh = $('sheet').getBoundingClientRect();
    if (r.bottom > sh.top - 8) $('screen').scrollTop += r.bottom - sh.top + 40;
  }, 0);
}
function curEdit() { return (edits[skey()] || {})[selId]; }

const KIND = { t: 'טקסט', b: 'מסגרת / רקע', i: 'אייקון' };
function tabsFor(k) {
  if (k === 't') return [['color', 'צבע'], ['size', 'גודל'], ['text', 'טקסט'], ['move', 'מיקום'], ['note', 'הערה']];
  if (k === 'i') return [['color', 'צבע'], ['size', 'גודל'], ['move', 'מיקום'], ['note', 'הערה']];
  return [['color', 'צבע'], ['radius', 'פינות'], ['move', 'מיקום'], ['note', 'הערה']];
}
function openSheet() {
  const e = curEdit();
  $('sheet').hidden = false;
  $('shTitle').textContent = e.label;
  $('shSub').textContent = KIND[e.kind] + ' · ' + screenOf(cur).name;
  $('shParent').style.visibility = sel.parentElement && sel.parentElement.closest('[data-e]') ? 'visible' : 'hidden';
  $('screen').style.paddingBottom = ($('sheet').offsetHeight + 20) + 'px';
  drawTabs(); drawPane();
}
function closeSheet() {
  $('sheet').hidden = true;
  $('screen').style.paddingBottom = '';
  if (sel) sel.classList.remove('sel');
  sel = null; selId = null; before = null;
}
function drawTabs() {
  const e = curEdit();
  const mod = { color: e.color != null, size: e.size != null, radius: e.radius != null, text: e.text != null, move: !!(e.dx || e.dy), note: !!(e.note && e.note.trim()) };
  $('tabs').innerHTML = tabsFor(e.kind).map(([k, n]) => `<div class="tab${k === tab ? ' on' : ''}${mod[k] ? ' mod' : ''}" data-t="${k}">${n}</div>`).join('');
}
$('tabs').addEventListener('click', ev => { const t = ev.target.closest('.tab'); if (!t) return; tab = t.dataset.t; drawTabs(); drawPane(); });

/** שינוי חי: מעדכן את הרכיב על המסך מיד */
function live(patch) {
  const e = curEdit();
  Object.assign(e, patch);
  for (const k in patch) if (patch[k] === null) delete e[k];
  // חזרה לערך המקורי = בלי שינוי
  if (e.size != null && e.size === e.base.size) delete e.size;
  if (e.radius != null && e.radius === e.base.radius) delete e.radius;
  if (e.text != null && e.text === e.base.text) delete e.text;
  if (e.color != null && e.color.toUpperCase() === String(e.base.color).toUpperCase()) delete e.color;
  restyle(); drawTabs();
}
/** בונה את הרכיב מחדש מהמקור ומחיל את השינויים (כך גם ביטול חוזר בדיוק למקור) */
function restyle() {
  const y = $('screen').scrollTop;
  const id = selId;
  render();
  sel = findEl(id);
  if (sel) sel.classList.add('sel');
  $('screen').scrollTop = y;
}

const PALETTE = ['#FFFFFF', '#F2F2F2', '#E6FFFFFF', '#AAAAAA', '#8E8E93', '#636366', '#30D158', '#69F0AE', '#4CAF50', '#2E7D32',
  '#0A84FF', '#4C7DFF', '#5AA9FF', '#FF3B30', '#D50000', '#FF9F0A', '#F08C00', '#7E57C2', '#1B1B1D', '#2C2C2E', '#000000', '#00000000'];

function drawPane() {
  const e = curEdit(), b = e.base, p = $('pane');
  if (tab === 'color') {
    const now = e.color != null ? e.color : b.color;
    p.innerHTML = `<div class="sw-row">${PALETTE.map(c => `<div class="swc${String(now).toUpperCase() === c ? ' on' : ''}" data-c="${c}" style="background:${css(c)}${c === '#00000000' ? ';background-image:linear-gradient(45deg,#555 25%,transparent 25%,transparent 75%,#555 75%);background-size:10px 10px' : ''}"></div>`).join('')}</div>
      <div class="row2"><input type="color" id="cPick" value="${rgb6(now)}"><input type="text" id="cHex" value="${esc(now)}" dir="ltr"></div>
      <div class="hint">מקורי: <span dir="ltr">${esc(b.color)}</span></div>`;
    p.querySelectorAll('.swc').forEach(s => s.onclick = () => { live({ color: s.dataset.c }); drawPane(); });
    $('cPick').oninput = ev => { live({ color: ev.target.value.toUpperCase() }); $('cHex').value = ev.target.value.toUpperCase(); };
    $('cHex').onchange = ev => { const v = ev.target.value.trim(); if (/^#([0-9a-f]{6}|[0-9a-f]{8})$/i.test(v)) { live({ color: v.toUpperCase() }); drawPane(); } };
  } else if (tab === 'size' || tab === 'radius') {
    const k = tab, now = e[k] != null ? e[k] : b[k], max = k === 'radius' ? 60 : 140, min = k === 'radius' ? 0 : 6;
    p.innerHTML = `<div class="step"><div class="sbtn" id="sPlus">+</div><div class="val" id="sVal">${now}</div><div class="sbtn" id="sMinus">−</div></div>
      <input type="range" id="sRange" min="${min}" max="${max}" step="1" value="${now}">
      <div class="hint">מקורי: ${b[k]} · ${k === 'radius' ? 'עיגול הפינות' : e.kind === 't' ? 'גודל הגופן' : 'רוחב וגובה'}</div>`;
    const set = v => { v = Math.max(min, Math.min(max, v)); $('sVal').textContent = v; $('sRange').value = v; live({ [k]: v }); };
    $('sPlus').onclick = () => set(+$('sVal').textContent + 1);
    $('sMinus').onclick = () => set(+$('sVal').textContent - 1);
    $('sRange').oninput = ev => set(+ev.target.value);
  } else if (tab === 'text') {
    p.innerHTML = `<textarea id="tText">${esc(e.text != null ? e.text : b.text)}</textarea><div class="hint">שורה חדשה = Enter</div>`;
    $('tText').oninput = ev => { const y = ev.target.selectionStart; live({ text: ev.target.value }); };
  } else if (tab === 'move') {
    const desc = () => {
      const x = e.dx || 0, y = e.dy || 0;
      if (!x && !y) return 'במקום המקורי';
      return [x ? Math.abs(x) + (x > 0 ? ' ימינה' : ' שמאלה') : '', y ? Math.abs(y) + (y > 0 ? ' למטה' : ' למעלה') : ''].filter(Boolean).join(', ');
    };
    p.innerHTML = `<div class="arrows"><div></div><div class="sbtn" data-m="0,-2">↑</div><div></div>
      <div class="sbtn" data-m="2,0">→</div><div class="sbtn" data-m="0,2">↓</div><div class="sbtn" data-m="-2,0">←</div></div>
      <div class="hint" id="mDesc">${desc()}</div>`;
    p.querySelectorAll('[data-m]').forEach(s => s.onclick = () => {
      const [x, y] = s.dataset.m.split(',').map(Number);
      live({ dx: (e.dx || 0) + x, dy: (e.dy || 0) + y }); $('mDesc').textContent = desc();
    });
  } else if (tab === 'note') {
    p.innerHTML = `<textarea id="nText" placeholder="מה לשנות ברכיב הזה">${esc(e.note || '')}</textarea>`;
    $('nText').oninput = ev => { e.note = ev.target.value; drawTabs(); };
  }
}

function cancelEdit(close = true) {
  const m = edits[skey()] || {};
  if (selId && before) m[selId] = before;
  if (selId && m[selId] && !hasChange(m[selId])) delete m[selId];
  saveEdits();
  if (close) { closeSheet(); render(); }
}
$('shCancel').onclick = () => cancelEdit(true);
$('shSave').onclick = () => {
  commit(); closeSheet(); render();
  toast('נשמר ברשימת השינויים');
};
$('shReset').onclick = () => {
  const e = curEdit(); if (!e) return;
  ['color', 'size', 'radius', 'text', 'dx', 'dy', 'note'].forEach(k => delete e[k]);
  restyle(); drawTabs(); drawPane();
};
$('shParent').onclick = () => {
  const p = sel && sel.parentElement && sel.parentElement.closest('[data-e]');
  if (!p) return;
  const id = p.dataset.e;
  commit(); render(); const n = findEl(id); if (n) select(n);
};

// ---- מונה ורשימת השינויים ----
function allItems() {
  const out = [];
  for (const sk in edits) {
    const [sid, th] = sk.split('|');
    for (const id in edits[sk]) { const e = edits[sk][id]; if (hasChange(e)) out.push({ sk, sid, th, id, e }); }
  }
  return out;
}
function updCount() {
  const n = allItems().length;
  $('chg').textContent = n === 1 ? 'שינוי אחד' : n + ' שינויים';
  $('chg').classList.toggle('has', n > 0);
}
/** תיאור השינויים (HTML): ערכים בבידוד כיוון, כדי שהחץ יהיה מהמקור לחדש */
function describe(e) {
  const b = e.base, l = [], v = x => `<bdi dir="ltr">${esc(x)}</bdi>`;
  if (e.color != null) l.push(`צבע: ${v(b.color)} ← ${v(e.color)}`);
  if (e.size != null) l.push(`גודל: ${v(b.size)} ← ${v(e.size)}`);
  if (e.radius != null) l.push(`פינות: ${v(b.radius)} ← ${v(e.radius)}`);
  if (e.text != null) l.push(`טקסט: <bdi>"${esc(b.text)}"</bdi> ← <bdi>"${esc(e.text)}"</bdi>`);
  if (e.dx || e.dy) l.push('מיקום: ' + [e.dx ? Math.abs(e.dx) + (e.dx > 0 ? ' ימינה' : ' שמאלה') : '', e.dy ? Math.abs(e.dy) + (e.dy > 0 ? ' למטה' : ' למעלה') : ''].filter(Boolean).join(', '));
  if (e.note && e.note.trim()) l.push('הערה: ' + esc(e.note.trim()));
  return l.join('<br>');
}
function showChanges() {
  const items = allItems();
  const by = {};
  items.forEach(i => (by[i.sk] = by[i.sk] || []).push(i));
  let h = '';
  for (const sk in by) {
    const [sid, th] = sk.split('|');
    h += `<div class="grp">${esc(screenOf(sid).name)}${th === 'light' ? ' · בהיר' : ''}</div><div class="card">` +
      by[sk].map(i => `<div class="item" data-go="${esc(i.sk)}" data-id="${esc(i.id)}"><div class="grow"><div>${esc(i.e.label)}</div><div class="sub">${describe(i.e)}</div></div><div class="del" data-del="1">מחיקה</div></div>`).join('') + '</div>';
  }
  if (!items.length) h = '<div class="grp">אין שינויים. לוחצים על רכיב במסך כדי לשנות אותו</div>';
  $('chgList').innerHTML = h;
  $('sendBtn').textContent = items.length ? `שליחה (${items.length})` : 'שליחה';
  $('sendBtn').classList.toggle('off', !items.length);
  show('changes');
  loadSent();
}
$('chg').onclick = showChanges;
$('chgList').addEventListener('click', ev => {
  const it = ev.target.closest('.item'); if (!it) return;
  const sk = it.dataset.go, id = it.dataset.id;
  if (ev.target.closest('[data-del]')) {
    delete edits[sk][id]; saveEdits(); render(); showChanges(); return;
  }
  // מעבר לרכיב
  const [sid, th] = sk.split('|');
  cur = sid; dark = th === 'dark'; store.set('screen', cur); store.set('theme', dark ? 'dark' : 'light');
  hide('changes'); render();
  const el = $('screen').querySelector(`[data-e="${CSS.escape(id)}"]`);
  if (el) { el.scrollIntoView({ block: 'center' }); select(el); }
});

// ---- שליחה ----
$('sendBtn').onclick = async () => {
  const items = allItems();
  if (!items.length) return;
  if (!(window.Native && Native.loggedIn())) { $('loginErr').textContent = ''; show('login'); setTimeout(() => $('code').focus(), 100); return; }
  $('sendBtn').textContent = 'שולח…';
  const payload = items.map(i => {
    const e = i.e, c = {};
    if (e.color != null) c.color = { from: e.base.color, to: e.color };
    if (e.size != null) c.size = { from: e.base.size, to: e.size };
    if (e.radius != null) c.radius = { from: e.base.radius, to: e.radius };
    if (e.text != null) c.text = { from: e.base.text, to: e.text };
    if (e.dx || e.dy) c.move = { dx: e.dx || 0, dy: e.dy || 0 };
    if (e.note && e.note.trim()) c.note = e.note.trim();
    return { screen: i.sid, screenName: screenOf(i.sid).name, el: i.id, label: e.label, kind: e.kind, theme: i.th, changes: c };
  });
  const r = await nat('send', JSON.stringify(payload));
  let o = null; try { o = JSON.parse(r); } catch (_) {}
  if (o && o.login) { show('login'); $('sendBtn').textContent = `שליחה (${items.length})`; return; }
  if (!o || !o.batches) { toast('השליחה נכשלה – אין חיבור לשרת'); $('sendBtn').textContent = `שליחה (${items.length})`; return; }
  edits = {}; saveEdits(); render();
  toast('נשלח ✓ – כתוב לי "תטפל בעריכה"');
  showChanges(); drawSent(o);
};
$('loginBtn').onclick = async () => {
  const code = $('code').value.trim(); if (!code) return;
  $('loginBtn').textContent = 'נכנס…';
  const err = await nat('login', code);
  $('loginBtn').textContent = 'כניסה ושליחה';
  if (err) { $('loginErr').textContent = err; return; }
  hide('login'); $('code').value = '';
  $('sendBtn').onclick();
};

const ST = { sent: 'נשלח – ממתין לטיפול', working: 'בטיפול', done: 'בוצע' };
async function loadSent() {
  if (!(window.Native && Native.loggedIn())) { $('sentList').innerHTML = ''; return; }
  const r = await nat('state');
  let o = null; try { o = JSON.parse(r); } catch (_) {}
  if (o && o.batches) drawSent(o);
}
function drawSent(o) {
  const list = (o.batches || []).slice().reverse();
  if (!list.length) { $('sentList').innerHTML = ''; return; }
  const fmt = ms => { const d = new Date(ms); return pad2(d.getDate()) + '/' + pad2(d.getMonth() + 1) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()); };
  $('sentList').innerHTML = '<div class="grp">שליחות קודמות</div><div class="card">' + list.map(b =>
    `<div class="item"><div class="grow"><div>${fmt(b.at)} · ${b.items.length === 1 ? 'שינוי אחד' : b.items.length + ' שינויים'}</div>` +
    `<div class="sub st-${b.state}">${ST[b.state] || b.state}${b.msg ? ' · ' + esc(b.msg) : ''}</div></div></div>`).join('') + '</div>';
}

// ---- בחירת מסך ----
function showMenu() {
  const counts = {};
  allItems().forEach(i => counts[i.sid] = (counts[i.sid] || 0) + 1);
  let h = '', g = '';
  SCREENS.forEach(s => {
    if (s.group !== g) { if (g) h += '</div>'; g = s.group; h += `<div class="grp">${esc(g)}</div><div class="card">`; }
    h += `<div class="item${s.id === cur ? ' cur' : ''}" data-s="${s.id}"><div class="grow">${esc(s.name)}</div>${counts[s.id] ? `<span class="cnt">${counts[s.id]}</span>` : ''}</div>`;
  });
  h += '</div><div class="grp">ערכת צבעים</div><div class="card">' +
    `<div class="item${dark ? ' cur' : ''}" data-theme="dark"><div class="grow">חשוך</div></div>` +
    `<div class="item${!dark ? ' cur' : ''}" data-theme="light"><div class="grow">בהיר</div></div></div>`;
  const ver = window.Native ? Native.version() : '';
  const us = window.Native ? Native.updateStatus() : '';
  h += `<div class="grp">עריכת צופר ${esc(ver)}${us ? ' · ' + esc(us) : ''}</div>`;
  $('menuList').innerHTML = h;
  show('menu');
}
$('scrName').onclick = showMenu;
$('menuList').addEventListener('click', ev => {
  const it = ev.target.closest('.item'); if (!it) return;
  if (it.dataset.s) { cur = it.dataset.s; store.set('screen', cur); }
  if (it.dataset.theme) { dark = it.dataset.theme === 'dark'; store.set('theme', dark ? 'dark' : 'light'); }
  hide('menu'); closeSheet(); render(); $('screen').scrollTop = 0;
});

// ---- עמודים, חזרה, הודעות ----
function show(id) { $(id).hidden = false; }
function hide(id) { $(id).hidden = true; }
document.querySelectorAll('[data-close]').forEach(b => b.onclick = () => hide(b.dataset.close));
window.onBack = () => {
  for (const id of ['login', 'changes', 'menu']) if (!$(id).hidden) { hide(id); return true; }
  if (!$('sheet').hidden) { cancelEdit(true); return true; }
  return false;
};
let toastT = 0;
function toast(s) { $('toast').textContent = s; $('toast').hidden = false; clearTimeout(toastT); toastT = setTimeout(() => $('toast').hidden = true, 2600); }

window.onUpdate = () => {
  if (!window.Native) return;
  const s = Native.updateStatus(), need = Native.needInstallPermission();
  $('updBanner').hidden = !s;
  $('updBanner').textContent = s + (need ? ' – לחץ לאישור' : '');
  $('updBanner').onclick = need ? () => Native.openInstallPermission() : null;
  $('stage').style.top = (46 + ($('updBanner').hidden ? 0 : $('updBanner').offsetHeight)) + 'px';
};

window.addEventListener('resize', () => { if ($('sheet').hidden) render(); });
render();
