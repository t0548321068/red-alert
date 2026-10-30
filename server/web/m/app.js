// צופר חירום - גרסת ה-PWA. אותו מסך כמו ב-APK: שורת חיוויים, כרטיס התרעה, כרטיס שעון ומוגן, פיד, התרעות אחרונות, שעון לילה.
// הנתונים מהשרת שלנו (כמו האתר): /api/live בזמן אמת, /archive, /api/state, /weather
'use strict';
const $ = id => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (_) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (_) {} }
};
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const pad = n => String(n).padStart(2, '0');
const hm = d => pad(d.getHours()) + ':' + pad(d.getMinutes());
const hms = d => hm(d) + ':' + pad(d.getSeconds());
const ic = (name, cls = '') => `<i class="ic ${cls}" style="--i:url(/m/ic/${name}.svg)"></i>`;
const ALL_COUNTRY = 'כל הארץ';
const fixAll = s => String(s).replace(/ברחבי הארץ/g, ALL_COUNTRY);

// ---- רמות וכותרות - אותם כללים כמו באפליקציה (מהשרת) ----
const PRE = 1, ALERT = 2, END = 3;
let CFG = { end: ['הסתיים', 'הוסר', 'ניתן לצאת'], pre: ['בדקות הקרובות', 'צפויות', 'מקדימה', 'איום', 'התקרבו'], endDisplay: 'האירוע הסתיים', prePrefix: null };
const levelOf = t => CFG.end.some(w => t.includes(w)) ? END : CFG.pre.some(w => t.includes(w)) ? PRE : ALERT;
function showTitle(t) {
  const l = levelOf(t);
  if (l === END) return CFG.endDisplay || t;
  const p = CFG.prePrefix;
  if (l === PRE && p && p.contains.some(w => t.includes(w)) && !t.startsWith(p.prefix)) return p.prefix + t;
  return t;
}
const COLOR = { [PRE]: '#F08C00', [END]: '#2E7D32', [ALERT]: '#D50000' };
function head(title, lvl) {
  if (lvl === PRE) { const t = showTitle(title); return ['מבזק פיקוד העורף', t.startsWith('התרעה מקדימה - ') ? t : 'התרעה מקדימה']; }
  if (lvl === END) return ['עדכון פיקוד העורף', 'האירוע הסתיים'];
  return [title, 'היכנסו למרחב המוגן'];
}
function alertIcon(title, lvl) {
  if (lvl === PRE) return 'ic_list_pre';
  if (lvl === END) return 'ic_list_end';
  if (title.includes('כלי טיס')) return 'ic_alert_aircraft';
  if (title.includes('מחבלים')) return 'ic_alert_infiltration';
  if (title.includes('רעידת אדמה')) return 'ic_alert_quake';
  if (title.includes('צונאמי')) return 'ic_alert_tsunami';
  if (title.includes('רדיולוגי')) return 'ic_alert_radio';
  if (title.includes('חומרים מסוכנים') || title.includes('כימי')) return 'ic_alert_hazmat';
  if (title.includes('רקטות') || title.includes('טילים')) return 'ic_alert_rocket';
  return 'ic_list_siren';
}
const iconScale = n => n === 'ic_list_pre' || n === 'ic_list_end' ? 1.4 : n === 'ic_alert_infiltration' ? 1.55 : 1;
const STAY_MS = 10 * 60e3;
const mmss = s => Math.floor(s / 60) + ':' + pad(s % 60);
function stageIcon(a, now = Date.now()) {
  if (a.level !== ALERT) return alertIcon(a.title, a.level);
  const enterEnd = a.firedAt + Math.max(0, a.shelter) * 1000;
  return now < enterEnd ? alertIcon(a.title, a.level) : now < enterEnd + STAY_MS ? 'ic_stage_stay' : 'ic_stage_wait';
}
function timer(a, now = Date.now()) {
  if (a.level !== ALERT) return null;
  const enterEnd = a.firedAt + Math.max(0, a.shelter) * 1000;
  if (now < enterEnd) return ['זמן התגוננות', mmss(Math.ceil((enterEnd - now) / 1000))];
  const left = Math.ceil((enterEnd + STAY_MS - now) / 1000);
  return left > 0 ? ['נשארים במרחב המוגן', mmss(left)] : ['', 'ממתינים להודעת סיום אירוע'];
}
const expired = (a, now = Date.now()) => now - a.firedAt > (a.level === END ? 10 : a.level === PRE ? 30 : 60) * 60e3;

// ---- הגדרות (נשמרות בדפדפן) ----
let enabled = store.get('m_enabled', true);
let soundOn = store.get('m_sound', true);       // הצופר עצמו נפתח רק אחרי נגיעה ראשונה במסך (דרישת הדפדפן)
let nearOn = store.get('m_near', true);
let extra = store.get('m_extra', []);            // יישובים ואזורים נוספים
let audioOk = false;

// ---- יישובים: זמני התגוננות ומחוזות ----
let MIGUN = {}, DIST = {};
const shelterOf = n => MIGUN[n] != null ? MIGUN[n] : null;
const shelterText = s => s <= 0 ? 'מיידי' : s < 60 ? `${s} שניות` : s === 60 ? 'דקה' : s === 90 ? 'דקה וחצי' : `${Math.floor(s / 60)} דקות`;

// ---- מיקום: אזור ההתרעה לפי הנקודה (בתוך המצולע של היישוב, אחרת הקרוב ביותר) ----
let loc = store.get('m_loc', null);   // {lat, lon, acc, at}
let nearArea = store.get('m_area', null);
function inPoly(lat, lon, p) {
  let inside = false;
  for (let i = 0, j = p.length - 1; i < p.length; j = i++) {
    const [yi, xi] = p[i], [yj, xj] = p[j];
    if ((yi > lat) !== (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}
function areaAt(lat, lon) {
  let best = null, bd = 1e9;
  for (const n in ALL_AREAS) {
    const a = ALL_AREAS[n];
    if (a.p && inPoly(lat, lon, a.p)) return n;
    if (a.c) { const d = (a.c[0] - lat) ** 2 + ((a.c[1] - lon) * 0.85) ** 2; if (d < bd) { bd = d; best = n; } }
  }
  return bd < 0.0004 ? best : null;   // עד ~2 ק"מ מהמרכז
}
let locBusy = false, locDenied = false;
function refreshLocation(force) {
  if (!('geolocation' in navigator) || locBusy) return;
  if (!force && loc && Date.now() - loc.at < 60e3) return;
  locBusy = true;
  navigator.geolocation.getCurrentPosition(p => {
    locBusy = false; locDenied = false;
    loc = { lat: p.coords.latitude, lon: p.coords.longitude, acc: Math.round(p.coords.accuracy), at: Date.now() };
    store.set('m_loc', loc);
    const a = areaAt(loc.lat, loc.lon); if (a) { nearArea = a; store.set('m_area', a); }
    renderArea(); renderIndicators(); loadWeather(true);
  }, e => { locBusy = false; if (e.code === 1) locDenied = true; renderIndicators(); renderArea(); },
  { enableHighAccuracy: true, timeout: 20000, maximumAge: force ? 0 : 60000 });
}
const myAreas = () => [...(nearOn && nearArea ? [nearArea] : []), ...extra];
function isMine(names) {
  const my = myAreas();
  if (my.includes(ALL_COUNTRY)) return true;
  return names.some(n => my.includes(n) || (DIST[n] && my.includes(DIST[n])));
}

// ---- התרעה פעילה (כמו Prefs.activeAlert באפליקציה) ----
let active = store.get('m_active', null);   // {title, level, firedAt, shelter, body, source, areas}
let dismissed = store.get('m_dismissed', 0);
const SRC = { oref: 'פיקוד העורף', history: 'פיקוד העורף', archive: 'פיקוד העורף', tzofar: 'צופר' };
const srcName = k => SRC[k] || (String(k).startsWith('tg:') ? 'טלגרם' : k || '');

// ---- המסך הראשי ----
function setTint(level) {
  let c = '#000000';
  if (level) {
    const h = COLOR[level], f = 0.30;
    const r = parseInt(h.slice(1, 3), 16), g = parseInt(h.slice(3, 5), 16), b = parseInt(h.slice(5, 7), 16);
    c = `rgb(${Math.round(r * f)},${Math.round(g * f)},${Math.round(b * f)})`;
  }
  document.body.style.background = c;
  document.querySelector('meta[name=theme-color]').content = c;
}
let cardKey = '', cardIcon = '';
function renderAlertCard() {
  const a = active, wrap = $('alertWrap'), card = $('alertCard');
  if (!a || expired(a) || dismissed === a.firedAt) { setTint(null); wrap.hidden = true; cardKey = ''; return; }
  setTint(a.level);
  const key = JSON.stringify(a);
  if (key !== cardKey) {
    cardKey = key; cardIcon = '';
    const [t1, t2] = head(a.title, a.level);
    card.style.background = COLOR[a.level];
    card.innerHTML = `<i class="ic aic" id="aIc"></i><div class="t1">${esc(t1)}</div><div class="t2">${esc(t2)}</div>
      ${a.body ? `<div class="body">${esc(fixAll(a.body))}</div>` : ''}
      ${a.level === ALERT ? '<div class="lbl" id="aLbl"></div><div class="tmr" id="aTmr"></div>' : ''}
      ${a.source ? `<div class="src">מקור ההתרעה: ${esc(a.source)}</div>` : ''}`;
    wrap.hidden = false;
  }
  const si = stageIcon(a);
  if (si !== cardIcon) { cardIcon = si; $('aIc').style.setProperty('--i', `url(/m/ic/${si}.svg)`); }
  const t = timer(a);
  if (t) { $('aLbl').textContent = t[0]; $('aLbl').hidden = !t[0]; $('aTmr').textContent = t[1]; $('aTmr').classList.toggle('small', !t[0]); }
}
$('alertX').onclick = () => { if (active) { dismissed = active.firedAt; store.set('m_dismissed', dismissed); } renderAlertCard(); };
$('alertCard').onclick = () => { if (active) openMap(active.areas || [], active.level, showTitle(active.title)); };

// מצב ההגנה: מוגן (ירוק) / אין רשת (כתום) / לא מוגן (אדום)
let liveState = 'connecting';   // connecting / on / off
const online = () => navigator.onLine !== false;
function renderProtect() {
  const noNet = enabled && (!online() || liveState === 'off');
  const card = $('clockCard');
  card.className = 'clockcard ' + (noNet ? 'nonet' : enabled ? 'on' : 'off');
  const sh = $('shield');
  if (enabled && !noNet) sh.innerHTML = '<img src="/m/ic/ic_shield_green.svg" alt="">';
  else sh.innerHTML = `<i class="ic" style="--i:url(/m/ic/ic_shield_outline.svg);color:${noNet ? '#FF9F0A' : '#FF453A'}"></i>`;
  // אותם צבעים כמו באפליקציה (שקיפות 6% / 14%)
  $('haloOuter').style.background = noNet ? 'rgba(255,159,10,.06)' : enabled ? 'rgba(105,240,174,.06)' : 'rgba(255,69,58,.06)';
  $('haloInner').style.background = noNet ? 'rgba(255,159,10,.14)' : enabled ? 'rgba(105,240,174,.14)' : 'rgba(255,69,58,.14)';
  const l = $('cLabel');
  l.textContent = noNet ? 'אין רשת – התרעות לא יגיעו' : enabled ? 'מוגן' : 'לא מוגן';
  l.style.color = noNet ? '#FF9F0A' : enabled ? '#69F0AE' : '#FF453A';
  sweep(enabled && !noNet);
}
// אנימציית מוגן: זוהר שמאיר ונכבה + גל רדאר שיוצא מהמגן (מחזור של 2.6 שניות)
let sweepOn = false;
function sweep(on) { sweepOn = on; if (!on) { $('glow').style.opacity = 0; $('ring').style.opacity = 0; } }
function sweepFrame(ts) {
  if (sweepOn) {
    const t = (ts % 2600) / 2600;
    $('glow').style.opacity = t < 0.35 ? t / 0.35 : Math.max(0, 1 - (t - 0.35) / 0.45);
    const r = (t - 0.3) / 0.6, ring = $('ring');
    if (r >= 0 && r <= 1) { const e = 1 - (1 - r) * (1 - r), s = 1 + (56 / 42 - 1) * e; ring.style.transform = `scale(${s})`; ring.style.opacity = 0.75 * (1 - r); }
    else ring.style.opacity = 0;
  }
  requestAnimationFrame(sweepFrame);
}
requestAnimationFrame(sweepFrame);

function renderArea() {
  const v = !nearOn ? 'כבוי' : nearArea || (locDenied ? 'אין הרשאת מיקום' : 'מאתר…');
  const s = nearOn && nearArea ? shelterOf(nearArea) : null;
  $('cArea').innerHTML = `<div class="k">אזור התרעה</div><div class="v">${esc(v)}</div>
    ${s != null ? `<div class="s">⏱ זמן התגוננות: ${shelterText(s)}</div>` : ''}<hr>`;
}
function renderExtra() {
  const towns = extra.filter(n => n === ALL_COUNTRY || ALL_AREAS[n]), dists = extra.filter(n => !towns.includes(n));
  const row = (n, sub) => `<div class="row"><span>${esc(n)}</span>${sub ? `<span>${esc(sub)}</span>` : ''}</div>`;
  let h = '';
  if (towns.length) h += '<div class="sec">יישובים</div>' + towns.map(n => row(n, n === ALL_COUNTRY ? '' : DIST[n] || '')).join('');
  if (dists.length) h += '<div class="sec">אזורים</div>' + dists.map(n => row(n, '')).join('');
  $('areasSlot').innerHTML = `<div class="extra"><div class="h">יישובים ואזורים נוספים</div><div class="list">${h || '<div class="none">לא נבחרו</div>'}</div></div>`;
}

// ---- שעון, ברכה ותאריך ----
const DAYS = ['ראשון', 'שני', 'שלישי', 'רביעי', 'חמישי', 'שישי', 'שבת'];
function greeting(d) {
  const h = d.getHours();
  const jan = new Date(d.getFullYear(), 0, 1).getTimezoneOffset(), summer = d.getTimezoneOffset() < jan;
  if (h >= 5 && h <= 11) return 'בוקר טוב';
  if (summer) return h <= 15 ? 'צהריים טובים' : h <= 18 ? 'אחר צהריים טובים' : h <= 21 ? 'ערב טוב' : 'לילה טוב';
  return h >= 12 && h <= 14 ? 'צהריים טובים' : h >= 15 && h <= 17 ? 'אחר צהריים טובים' : h >= 18 && h <= 20 ? 'ערב טוב' : 'לילה טוב';
}
const clockHtml = s => s.replace(/:/g, '<span class="c">:</span>');
function renderClock() {
  const d = new Date();
  $('greet').textContent = greeting(d);
  $('cTime').innerHTML = clockHtml(hms(d));
  $('cDay').textContent = `יום ${DAYS[d.getDay()]} · ${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`;
  if (!$('pClock').hidden) $('nTime').innerHTML = clockHtml(hms(d));
}

// ---- מזג אוויר (מהשרת, לפי המיקום) ----
const WX = c => c === 0 ? null : c <= 2 ? '🌤' : c === 3 ? '☁️' : c === 45 || c === 48 ? '🌫' : c >= 51 && c <= 57 ? '🌦' : c >= 61 && c <= 67 ? '🌧' : c >= 71 && c <= 77 ? '❄️' : c >= 80 && c <= 82 ? '🌦' : c >= 95 ? '⛈' : '🌡';
let wxAt = 0;
function loadWeather(force) {
  if (!loc) { $('cWx').textContent = locDenied ? '' : '📍 הצג מזג אוויר לפי מיקום'; $('cWx').style.color = 'var(--blue)'; return; }
  if (!force && Date.now() - wxAt < 10 * 60e3) return;
  wxAt = Date.now();
  fetch(`/weather?lat=${loc.lat.toFixed(4)}&lon=${loc.lon.toFixed(4)}`).then(r => r.json()).then(w => {
    const icon = w.code === 0 ? (w.isDay ? '☀️' : '🌙') : w.code <= 2 && !w.isDay ? '☁️' : WX(w.code);
    $('cWx').textContent = [`${icon} ${w.temp}°`, w.place].filter(Boolean).join(' · ');
    $('cWx').style.color = '';
  }).catch(() => { wxAt = 0; });
}
$('cWx').onclick = () => refreshLocation(true);

// ---- שורת החיוויים ----
let srv = null;   // /api/state
function setInd(id, icon, label, color, blink) {
  const b = $(id), i = b.querySelector('.ic');
  i.style.setProperty('--i', `url(/m/ic/${icon}.svg)`); i.style.color = color;
  b.querySelector('span').textContent = label;
  b.classList.toggle('blink', !!blink);
}
function renderIndicators() {
  const G = 'var(--green)', O = 'var(--orange)', R = 'var(--red)', M = 'var(--muted)';
  if (enabled) setInd('iProt', 'ic_shield_ind', 'מוגן', G); else setInd('iProt', 'ic_shield_ind_off', 'לא מוגן', R);
  if (!soundOn) setInd('iSnd', 'ic_volume_off', 'כבוי', R);
  else setInd('iSnd', 'ic_set_sound', 'צלילים', audioOk ? G : O, !audioOk);
  const c = navigator.connection || {}, ok = liveState === 'on';
  if (!online()) setInd('iNet', 'ic_globe', 'אין רשת', R);
  else setInd('iNet', 'ic_globe', c.type === 'wifi' ? 'Wi-Fi' : c.type === 'cellular' ? 'נתונים' : 'מחובר', ok ? G : O);
  if (locDenied || !('geolocation' in navigator)) setInd('iLoc', 'ic_location', 'כבוי', M);
  else setInd('iLoc', 'ic_location', loc ? `${loc.acc} מ׳` : '—', loc && loc.acc <= 100 ? G : O);
  if (!enabled) setInd('iSrv', 'ic_antenna', 'לא מחובר', M);
  else if (liveState === 'on') setInd('iSrv', 'ic_antenna', 'מחובר', G);
  else if (liveState === 'connecting') setInd('iSrv', 'ic_antenna', 'מתחבר…', G, true);
  else setInd('iSrv', 'ic_antenna', 'לא מחובר', R);
  setInd('iVer', 'ic_info', srv && srv.version ? 'v' + srv.version : 'v—', G);
}
$('iProt').onclick = () => { enabled = !enabled; store.set('m_enabled', enabled); if (!enabled) stopSiren(); refreshAll(); };
$('iSnd').onclick = () => { if (!audioOk) { unlockAudio(); return; } soundOn = !soundOn; store.set('m_sound', soundOn); if (!soundOn) stopSiren(); renderIndicators(); };
$('iNet').onclick = () => sheet(online() ? 'מחובר לאינטרנט' : 'אין חיבור לאינטרנט',
  liveState === 'on' ? 'החיבור לשרת ההתרעות פעיל' : 'מתחבר לשרת ההתרעות…');
$('iLoc').onclick = () => refreshLocation(true);
$('iSrv').onclick = showSources;
$('iVer').onclick = () => sheet('צופר חירום', `גרסת האפליקציה האחרונה: ${srv && srv.version || '–'}\nנתוני יישובים: ${srv && srv.data || '–'}\n\nזו גרסת הדפדפן (PWA). התרעות עם צופר - רק כשהיא פתוחה.`);

const SOURCES = [['oref', 'פיקוד העורף – חי'], ['history', 'פיקוד העורף – היסטוריה'], ['archive', 'פיקוד העורף – ארכיון'], ['tzofar', 'צופר'],
  ['tg:PikudHaOref_all', 'טלגרם – פיקוד העורף'], ['tg:tzevaadomm', 'טלגרם – צופר'], ['tg:CumtaAlertsChannel', 'טלגרם – כומתה'], ['tg:Radar_Alerts', 'טלגרם – רדאר']];
function showSources() {
  const up = new Set(srv ? srv.up : []), n = SOURCES.filter(([k]) => up.has(k)).length;
  sheet(`מקורות ההתרעות · ${n}/${SOURCES.length}`, SOURCES.map(([k, name]) => `${up.has(k) ? '🟢' : '🔴'} ${name}`).join('\n'));
}
function loadState() {
  fetch('/api/state').then(r => { if (r.status === 401) location.reload(); return r.json(); })
    .then(s => { srv = s; renderIndicators(); }).catch(() => {});
}

// ---- פיד: ההתרעות של 30 הדקות האחרונות, אחת בכל פעם ----
let recent = [];   // {t, b, ts}
let feedIdx = 0, feedKey = '', feedTick = 0;
function feedItems() {
  const since = Date.now() - 30 * 60e3;
  return recent.filter(e => e.ts >= since).slice(0, 30);
}
function feedHtml(e) {
  const lvl = levelOf(e.t), t = lvl === END ? 'האירוע הסתיים' : e.t.includes('בדקות הקרובות') ? 'מקדימה' : e.t;
  const n = alertIcon(e.t, lvl), i = `<i class="ic fi" style="--i:url(/m/ic/${n}.svg);color:${COLOR[lvl]};transform:scale(${iconScale(n)})"></i>`;
  return `${hm(new Date(e.ts))}:${pad(new Date(e.ts).getSeconds())}\n${i} ${esc(t)} ${i}\n<span class="ar">${esc(fixAll(e.b))}</span>`;
}
function renderFeed() {
  const items = feedItems(), key = items.map(e => e.ts + e.t).join('|'), f = $('feed');
  if (key !== feedKey) {
    feedKey = key; feedIdx = 0; feedTick = 0;
    $('feedCard').hidden = !items.length;
    if (items.length) f.innerHTML = feedHtml(items[0]);
    return;
  }
  if (items.length < 2 || ++feedTick < 3) return;
  feedTick = 0; feedIdx = (feedIdx + 1) % items.length;
  f.classList.add('out');
  setTimeout(() => { f.innerHTML = feedHtml(items[feedIdx]); f.classList.remove('out'); f.classList.add('in');
    requestAnimationFrame(() => requestAnimationFrame(() => f.classList.remove('in'))); }, 220);
}

// ---- צופר והתראות ----
const siren = $('siren');
function unlockAudio() {
  if (audioOk) return;
  siren.muted = true;
  siren.play().then(() => { siren.pause(); siren.currentTime = 0; siren.muted = false; audioOk = true; renderIndicators(); }).catch(() => {});
}
document.addEventListener('pointerdown', unlockAudio, { capture: true });
function playSiren() { if (!soundOn || !audioOk) return; siren.currentTime = 0; siren.play().catch(() => {}); }
function stopSiren() { siren.pause(); siren.currentTime = 0; }
let swReg = null;
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').then(r => { swReg = r; }).catch(() => {});
function notify(a) {
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  const [t1] = head(a.title, a.level);
  const opt = { body: fixAll(a.body), icon: '/icon.png', badge: '/icon.png', tag: 'alert', requireInteraction: a.level === ALERT };
  if (swReg) swReg.showNotification(t1, opt).catch(() => {});
  else try { new Notification(t1, opt); } catch (_) {}
}

// ---- התרעות חיות ----
const seen = new Set(store.get('m_seen', []));
function onAlert(x, quiet) {
  const key = x.title + '|' + x.at;
  addRecent({ t: x.title, b: x.areas.join(', '), ts: x.at });
  if (!enabled) return;
  const lvl = levelOf(x.title);
  const mine = x.areas.filter(n => isMine([n]));
  if (!mine.length || seen.has(key + '|' + mine.join(','))) return;
  seen.add(key + '|' + mine.join(',')); store.set('m_seen', [...seen].slice(-200));
  // "התרעה" לא נדרסת ע"י מקדימה בזמן ההתגוננות
  if (active && active.level === ALERT && lvl === PRE && !expired(active) && x.at - active.firedAt < STAY_MS) return;
  const sh = mine.map(shelterOf).filter(s => s != null);
  active = { title: x.title, level: lvl, firedAt: x.at, shelter: sh.length ? Math.min(...sh) : 90,
    body: mine.join(', '), source: srcName(x.src), areas: mine };
  store.set('m_active', active);
  cardKey = ''; renderAlertCard();
  if (quiet) return;
  notify(active);
  if (lvl === ALERT) playSiren(); else stopSiren();
}
let es;
function connect() {
  es = new EventSource('/api/live');
  let quiet = true;   // ההתרעות שנשלחות בחיבור (חצי השעה האחרונה) - בשקט
  es.onopen = () => { liveState = 'on'; refreshAll(); };
  es.addEventListener('ready', () => { quiet = false; });
  es.addEventListener('alert', e => { try { onAlert(JSON.parse(e.data), quiet); } catch (_) {} });
  es.onerror = () => {
    liveState = es.readyState === 2 ? 'off' : 'connecting'; refreshAll();
    if (es.readyState === 2) setTimeout(() => fetch('/api/state').then(r => { if (r.status === 401) location.reload(); else connect(); }).catch(() => setTimeout(connect, 5000)), 3000);
  };
}

// ---- התרעות אחרונות ----
function addRecent(e) {
  if (recent.some(x => x.t === e.t && Math.abs(x.ts - e.ts) < 60e3 && x.b === e.b)) return;
  recent.push(e); recent.sort((a, b) => b.ts - a.ts);
  if (!$('pRecent').hidden) renderRecent();
}
function loadArchive() {
  return fetch('/archive?since=' + (Date.now() - 30 * 24 * 3600e3)).then(r => r.json()).then(list => {
    for (const e of list) addRecent(e);
    renderFeed(); if (!$('pRecent').hidden) renderRecent();
  }).catch(() => {});
}
let recentMine = false, recentShown = 80;
function fullDay(ts) {
  const d = new Date(ts), date = `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`;
  const mid = x => { const k = new Date(x); k.setHours(0, 0, 0, 0); return k.getTime(); };
  const days = Math.round((mid(Date.now()) - mid(ts)) / 864e5);
  const nick = days <= 0 ? 'היום' : days === 1 ? 'אתמול' : days === 2 ? 'שלשום' : days < 7 ? '' : days < 14 ? 'לפני שבוע'
    : days < 21 ? 'לפני שבועיים' : days < 30 ? 'לפני 3 שבועות' : days < 60 ? 'לפני חודש' : '';
  return nick ? `${nick} · ${date}` : date;
}
function renderRecent() {
  const mineList = recent.filter(e => isMine(e.b.split(', ')));
  const items = recentMine ? mineList : recent;
  $('seg').innerHTML = [[false, 'הכל', recent.length], [true, 'שלי', mineList.length]]
    .map(([m, n, c]) => `<button data-m="${m ? 1 : 0}" class="${recentMine === m ? 'sel' : ''}">${n} (${c})</button>`).join('');
  if (!items.length) { $('recentList').innerHTML = `<div class="empty">${recentMine ? 'אין התראות באזורים שלך' : 'אין התראות עדיין'}</div>`; return; }
  let h = '', day = null;
  items.slice(0, recentShown).forEach((e, i) => {
    const dl = fullDay(e.ts), lvl = levelOf(e.t), n = alertIcon(e.t, lvl);
    if (dl !== day) { if (day !== null) h += '</div>'; day = dl; h += `<div class="day">${dl}</div><div class="rcard">`; }
    const col = lvl === PRE ? '#F08C00' : lvl === END ? '#2E7D32' : '#E53935';
    h += `<div class="rrow" data-i="${recent.indexOf(e)}"><div class="dot" style="background:${col}"><i class="ic" style="--i:url(/m/ic/${n}.svg);transform:scale(${iconScale(n)})"></i></div>
      <div class="tx"><div class="a">${esc(lvl === PRE ? 'התראה מקדימה' : e.t)}</div><div class="b">${esc(fixAll(e.b))}</div></div><div class="tm">${hm(new Date(e.ts))}</div></div>`;
  });
  $('recentList').innerHTML = h + '</div>';
}
$('seg').onclick = e => { const b = e.target.closest('button'); if (!b) return; recentMine = b.dataset.m === '1'; recentShown = 80; renderRecent(); };
$('pRecent').onscroll = () => { const p = $('pRecent'); if (p.scrollTop + p.clientHeight > p.scrollHeight - 1500 && recentShown < recent.length) { recentShown += 80; renderRecent(); } };
$('recentList').onclick = e => {
  const r = e.target.closest('.rrow'); if (!r) return;
  const ev = recent[+r.dataset.i];
  openMap(ev.b.split(', '), levelOf(ev.t), showTitle(ev.t) + ' · ' + hm(new Date(ev.ts)));
};

// ---- מפה ----
let map = null, mapLayer = null;
function openMap(names, lvl, title) {
  openPage('pMap');
  $('mapTitle').textContent = title || 'מפה';
  if (!map) {
    map = L.map('map', { zoomControl: false, attributionControl: false, minZoom: 7, maxZoom: 18 }).setView([31.6, 34.9], 8);
    try { maplibregl.setRTLTextPlugin('https://cdn.jsdelivr.net/npm/@mapbox/mapbox-gl-rtl-text@0.3.0/dist/mapbox-gl-rtl-text.js', false); } catch (_) {}
    try { L.maplibreGL({ style: mapStyle(true), interactive: false }).addTo(map); } catch (_) {}
    mapLayer = L.layerGroup().addTo(map);
  }
  setTimeout(() => {
    map.invalidateSize(); mapLayer.clearLayers();
    const c = COLOR[lvl || ALERT], pts = [];
    for (const n of names) {
      const a = ALL_AREAS[n]; if (!a) continue;
      (a.p ? L.polygon(a.p, { color: c, weight: 1, fillColor: c, fillOpacity: 0.45 }) : L.circleMarker(a.c, { radius: 7, color: c, fillOpacity: 0.6 })).addTo(mapLayer);
      pts.push(a.c);
    }
    if (pts.length) map.fitBounds(L.latLngBounds(pts).pad(0.3), { maxZoom: 13 });
    else if (loc) map.setView([loc.lat, loc.lon], 11);
  }, 30);
}
$('goMap').onclick = () => {
  const since = Date.now() - 60 * 60e3, ev = recent.filter(e => e.ts >= since);
  openMap([...new Set(ev.flatMap(e => e.b.split(', ')))], ALERT, 'מפת ההתרעות · השעה האחרונה');
};

// ---- הגדרות ----
const NOTIF = () => 'Notification' in window ? Notification.permission : 'denied';
function renderSettings() {
  const sw = on => `<span class="vsep"></span><span class="sw ${on ? 'on' : ''}"></span>`;
  const row = (id, icon, color, t, sub, right = '') => `<div class="srow" data-k="${id}"><span class="sic" style="background:${color}">${ic(icon)}</span>
    <div class="st"><div>${t}</div>${sub ? `<div>${sub}</div>` : ''}</div>${right}</div>`;
  const ex = extra.map((n, i) => `<div class="srow" data-del="${i}"><div class="st"><div>${esc(n)}</div>${DIST[n] ? `<div style="color:var(--muted)">${esc(DIST[n])}</div>` : ''}</div><span class="del">הסר</span></div>`).join('');
  $('settings').innerHTML = `
    <div class="scard">
      ${row('enabled', 'ic_set_shield', '#D50000', 'הגנה', enabled ? 'פעילה' : 'כבויה', sw(enabled))}
      ${row('sound', 'ic_set_sound', '#8E24AA', 'צופר', soundOn ? (audioOk ? 'פועל' : 'גע במסך כדי להפעיל') : 'כבוי', sw(soundOn))}
      ${row('notif', 'ic_set_bell', '#FB8C00', 'התראות', NOTIF() === 'granted' ? 'מאושרות' : NOTIF() === 'denied' ? 'חסומות בדפדפן' : 'לחץ לאישור')}
    </div>
    <div class="scap">התרעות</div>
    <div class="scard">
      ${row('near', 'ic_location', '#00897B', 'התרעות לפי המיקום', nearOn ? nearArea || 'מאתר…' : 'כבוי', sw(nearOn))}
    </div>
    <div class="scap">יישובים ואזורים נוספים</div>
    <div class="scard">${ex}
      <div class="addbox"><input id="addIn" placeholder="הוסף יישוב או אזור…" autocomplete="off"><button id="addBtn">הוסף</button></div>
      <div class="sugg" id="sugg"></div>
    </div>
    <div class="scard">
      ${row('desk', 'ic_set_display', '#7E57C2', 'האתר המלא', 'מכשירים, הודעות ומצב השרת')}
      ${row('logout', 'ic_set_lock', '#546E7A', 'יציאה', '')}
    </div>`;
  const inp = $('addIn'), names = () => [ALL_COUNTRY, ...new Set(Object.values(DIST)), ...Object.keys(ALL_AREAS)];
  const add = v => { v = v.trim(); if (!v || !names().includes(v) || extra.includes(v)) return; extra.push(v); store.set('m_extra', extra); renderSettings(); renderExtra(); };
  inp.oninput = () => {
    const q = inp.value.trim();
    $('sugg').innerHTML = q ? names().filter(n => n.includes(q) && !extra.includes(n)).slice(0, 8).map(n => `<div>${esc(n)}</div>`).join('') : '';
  };
  $('sugg').onclick = e => { if (e.target.parentNode === $('sugg')) add(e.target.textContent); };
  $('addBtn').onclick = () => add(inp.value);
  inp.onkeydown = e => { if (e.key === 'Enter') add(inp.value); };
}
$('settings').onclick = e => {
  const d = e.target.closest('[data-del]');
  if (d) { extra.splice(+d.dataset.del, 1); store.set('m_extra', extra); renderSettings(); renderExtra(); return; }
  const r = e.target.closest('[data-k]'); if (!r) return;
  const k = r.dataset.k;
  if (k === 'enabled') { enabled = !enabled; store.set('m_enabled', enabled); if (!enabled) stopSiren(); }
  if (k === 'sound') { soundOn = !soundOn; store.set('m_sound', soundOn); if (!soundOn) stopSiren(); }
  if (k === 'notif' && 'Notification' in window) Notification.requestPermission().then(renderSettings);
  if (k === 'near') { nearOn = !nearOn; store.set('m_near', nearOn); if (nearOn) refreshLocation(true); }
  if (k === 'desk') location.href = '/?full=1';
  if (k === 'logout') location.href = '/api/logout';
  renderSettings(); refreshAll();
};

// ---- בדיקת התרעה (הכפתור האדום) ----
function chooseTest() {
  sheet('בדיקת התרעה', 'בחר סוג התרעה לבדיקה. היא מוצגת רק כאן ולא נשלחת לאף אחד.', [
    ['ירי רקטות', () => testAlert('ירי רקטות וטילים')],
    ['מקדימה', () => testAlert('בדקות הקרובות צפויות להתקבל התרעות באזורך')],
    ['סיום', () => testAlert('האירוע הסתיים')]]);
}
function testAlert(title) {
  const n = nearArea || extra.find(x => ALL_AREAS[x]) || 'ירושלים - צפון';
  const lvl = levelOf(title), s = shelterOf(n);
  active = { title, level: lvl, firedAt: Date.now(), shelter: s != null ? s : 90, body: n, source: 'בדיקה', areas: [n] };
  dismissed = 0; cardKey = ''; renderAlertCard();
  if (lvl === ALERT) playSiren(); else stopSiren();
}

// ---- חלון תחתון ----
function sheet(title, body, buttons) {
  const btns = buttons || [['סגור', null]];
  $('sheet').innerHTML = `<div class="sh1">${esc(title)}</div><div class="sh2">${esc(body)}</div>
    <div class="sbtns">${btns.map(([t], i) => (i ? '<i></i>' : '') + `<button data-b="${i}">${esc(t)}</button>`).join('')}</div>`;
  $('sheetBg').hidden = false;
  $('sheet').onclick = e => { const b = e.target.closest('[data-b]'); if (!b) return; $('sheetBg').hidden = true; const f = btns[+b.dataset.b][1]; if (f) f(); };
}
$('sheetBg').onclick = e => { if (e.target === $('sheetBg')) $('sheetBg').hidden = true; };

// ---- ניווט בין העמודים (כפתור "חזרה" של הטלפון סוגר עמוד) ----
function openPage(id) { $(id).hidden = false; history.pushState({ p: id }, ''); }
function closeTop() {
  for (const id of ['pClock', 'pMap', 'pSettings', 'pRecent']) if (!$(id).hidden) { $(id).hidden = true; return true; }
  return false;
}
window.onpopstate = () => { if (!$('sheetBg').hidden) { $('sheetBg').hidden = true; return; } closeTop(); };
document.querySelectorAll('[data-back]').forEach(b => b.onclick = () => history.back());
$('goRecent').onclick = () => { recentShown = 80; renderRecent(); openPage('pRecent'); $('pRecent').scrollTop = 0; };
$('goSettings').onclick = () => { renderSettings(); openPage('pSettings'); };
let wake = null;
$('goClock').onclick = () => {
  openPage('pClock'); renderClock();
  try { document.documentElement.requestFullscreen().catch(() => {}); } catch (_) {}
  if ('wakeLock' in navigator) navigator.wakeLock.request('screen').then(w => { wake = w; }).catch(() => {});
};
$('pClock').onclick = () => { if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); if (wake) { wake.release(); wake = null; } history.back(); };

// כפתור הבדיקה הצף (כמו באפליקציה)
const fab = document.createElement('button');
fab.id = 'fab'; fab.innerHTML = ic('ic_list_siren');
fab.style.cssText = 'position:fixed;left:20px;bottom:calc(20px + env(safe-area-inset-bottom));width:56px;height:56px;border-radius:50%;background:#D50000;color:#fff;display:flex;align-items:center;justify-content:center;box-shadow:0 3px 8px rgba(0,0,0,.5);z-index:5';
fab.querySelector('.ic').style.width = fab.querySelector('.ic').style.height = '30px';
fab.onclick = chooseTest;
document.body.appendChild(fab);

// ---- הפעלה ----
function refreshAll() { renderProtect(); renderIndicators(); renderArea(); renderAlertCard(); }
window.addEventListener('online', refreshAll); window.addEventListener('offline', refreshAll);
document.addEventListener('visibilitychange', () => { if (!document.hidden) { refreshLocation(false); loadWeather(false); } });
fetch('/data/config.json').then(r => r.json()).then(c => { if (c.titles) CFG = Object.assign(CFG, c.titles); refreshAll(); }).catch(() => {});
fetch('/assets/migun.txt').then(r => r.text()).then(t => {
  for (const line of t.split('\n')) { const [n, s] = line.split('\t'); if (n && s) MIGUN[n.trim()] = +s; }
  renderArea();
}).catch(() => {});
fetch('/assets/districts.json').then(r => r.json()).then(d => { DIST = d; renderExtra(); }).catch(() => {});
renderExtra(); refreshAll(); renderClock();
loadArchive(); setInterval(loadArchive, 5 * 60e3);
loadState(); setInterval(loadState, 30e3);
refreshLocation(false); loadWeather(false); setInterval(() => loadWeather(false), 10 * 60e3);
connect();
setInterval(() => { renderClock(); renderAlertCard(); renderFeed(); }, 1000);
