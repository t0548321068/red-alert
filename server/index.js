// שרת ההתראות: כל המקורות שהטלפון בדק בעצמו - פיקוד העורף (חי, היסטוריה, ארכיון), צופר ו-4 ערוצי טלגרם.
// כל התרעה חדשה נשלחת מיד לכל הטלפונים דרך Firebase (נושא "alerts"), ופעם ב-10 דקות סימן חיים
// עם מצב המקורות. כשסימן החיים לא מגיע - הטלפון חוזר לבדוק בעצמו (גיבוי).
// מפתח השרת של Firebase: הקובץ service-account.json באותה תיקייה (לא נכנס לגיט).
const fs = require('fs');
const path = require('path');
const https = require('https');
const WebSocket = require('ws');
const admin = require('firebase-admin');

admin.initializeApp({ credential: admin.credential.cert(require(path.join(__dirname, 'service-account.json'))) });
const TOPIC = 'alerts';
const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---- שליחה ----
// הודעת Firebase מוגבלת ל-4KB - התרעה עם הרבה אזורים (כל הארץ) נשלחת בכמה חלקים
// אותה התרעה מכמה מקורות נשלחת פעם אחת: יישוב שכבר נשלח עם אותה כותרת ב-2 הדקות האחרונות - לא שוב
const sentAt = new Map();
const DEDUP_MS = 2 * 60 * 1000;
async function send(title, areas, src, at = Date.now()) {
  const now = Date.now();
  for (const [k, t] of sentAt) if (now - t > DEDUP_MS) sentAt.delete(k);
  areas = [...new Set(areas.map(a => String(a).trim()).filter(Boolean))].filter(a => !sentAt.has(title + '|' + a));
  if (!areas.length) return;
  for (const a of areas) sentAt.set(title + '|' + a, now);
  const chunks = [];
  let cur = [];
  for (const a of areas) {
    if (Buffer.byteLength(JSON.stringify([...cur, a])) > 3000) { chunks.push(cur); cur = []; }
    cur.push(a);
  }
  if (cur.length) chunks.push(cur);
  const msgs = chunks.map(part => ({
    topic: TOPIC,
    data: { type: 'alert', title, areas: part.join('|'), src, at: String(at) },
    android: { priority: 'high', ttl: 60 * 1000 },
  }));
  const push = async () => { for (const m of msgs) { try { await admin.messaging().send(m); } catch (e) { log('send failed', e.message); } } };
  await push();
  // אמינות: אותה הודעה שוב אחרי 5 שניות - אם הראשונה לא הגיעה. הטלפון מסנן כפילויות (אותו זמן התרעה)
  setTimeout(push, 5000);
  log('sent', src, title, areas.length + ' areas', chunks.length + ' msg');
}

// ---- מצב המקורות (נשלח בסימן החיים, והטלפון מציג אותו) ----
const okAt = {};
const ok = k => { okAt[k] = Date.now(); };
let tzofarOpen = false;
function upList() {
  const up = Object.keys(okAt).filter(k => Date.now() - okAt[k] < 5 * 60 * 1000);
  if (tzofarOpen && !up.includes('tzofar')) up.push('tzofar');
  return up;
}
async function heartbeat() {
  const up = upList();
  try {
    await admin.messaging().send({
      topic: TOPIC, data: { type: 'hb', up: up.join(','), at: String(Date.now()) },
      android: { priority: 'high', ttl: 5 * 60 * 1000 },
    });
  } catch (e) { log('hb failed', e.message); }
}
setInterval(heartbeat, 10 * 60 * 1000);
setTimeout(heartbeat, 20 * 1000);

// ---- בדיקה מהאפליקציה (כפתור "בדוק את השרתים"): מצב המקורות, ושליחת סימן חיים מיד ----
// סימן החיים עובר דרך Firebase - כך נבדקת כל הדרך עד הטלפון. לכל היותר פעם ב-30 שניות
const STARTED = Date.now();
let lastManualHb = 0;
require('http').createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname === '/archive') return serveArchive(req, res, +(u.searchParams.get('since') || 0));
  if (u.pathname === '/weather') return serveWeather(res, +u.searchParams.get('lat'), +u.searchParams.get('lon'));
  if (u.pathname === '/shabbat') return serveShabbat(res, +u.searchParams.get('lat'), +u.searchParams.get('lon'));
  if (u.pathname !== '/status') { res.writeHead(404); return res.end(); }
  if (Date.now() - lastManualHb > 30 * 1000) { lastManualHb = Date.now(); heartbeat(); }
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ up: upList(), started: STARTED, now: Date.now(), version: latest }));
}).listen(8080);

// ---- בקשת https פשוטה (חיבורים נפרדים, כדי לא לעכב את הבדיקה החיה של פיקוד העורף) ----
const slowAgent = new https.Agent({ keepAlive: true, maxSockets: 6 });
function get(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { agent: slowAgent, timeout: 8000, headers: { 'User-Agent': 'Mozilla/5.0 RedAlertPush', ...headers } }, res => {
      const bufs = [];
      res.on('data', d => bufs.push(d));
      res.on('end', () => {
        const b = Buffer.concat(bufs);
        let t = b.toString('utf8').replace(/^\uFEFF/, '').trim();
        if (t.includes('\u0000')) t = b.toString('utf16le').replace(/^\uFEFF/, '').trim();
        res.statusCode === 200 ? resolve(t) : reject(new Error('HTTP ' + res.statusCode));
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}

// ---- פיקוד העורף: הקובץ החי, כל שנייה, בחיבור קבוע ----
const agent = new https.Agent({ keepAlive: true, maxSockets: 2 });
let lastOrefId = '';
function pollOref() {
  const req = https.get({
    host: 'www.oref.org.il', path: '/WarningMessages/alert/alerts.json?t=' + Date.now(), agent, timeout: 4000,
    headers: { Referer: 'https://www.oref.org.il/', 'X-Requested-With': 'XMLHttpRequest', 'User-Agent': 'Mozilla/5.0 RedAlertPush' },
  }, res => {
    const bufs = [];
    res.on('data', d => bufs.push(d));
    res.on('end', () => {
      let txt = Buffer.concat(bufs).toString('utf8').replace(/^﻿/, '').trim();
      if (!txt && bufs.length) txt = Buffer.concat(bufs).toString('utf16le').replace(/^﻿/, '').trim();
      ok('oref');
      if (!txt) return;
      try {
        const j = JSON.parse(txt);
        if (!j.id || j.id === lastOrefId) return;
        lastOrefId = j.id;
        if (Array.isArray(j.data) && j.data.length) send(j.title || 'התראה', j.data, 'oref');
      } catch (e) { /* תשובה לא תקינה - בסבב הבא */ }
    });
  });
  req.on('timeout', () => req.destroy());
  req.on('error', () => {});
}
setInterval(pollOref, 1000);

// ---- צופר: חיבור push ----
const THREATS = { 0: 'ירי רקטות וטילים', 1: 'אירוע חומרים מסוכנים', 2: 'חדירת מחבלים', 3: 'רעידת אדמה', 4: 'חשש לצונאמי',
  5: 'חדירת כלי טיס עוין', 6: 'חשש לאירוע רדיולוגי', 7: 'חשש לאירוע כימי', 8: 'התרעות פיקוד העורף' };
const PRE = 'בדקות הקרובות צפויות להתקבל התרעות באזורך', END = 'האירוע הסתיים';
const cityIds = {};
for (const l of fs.readFileSync(path.join(__dirname, 'tzofar_ids.txt'), 'utf8').split('\n')) {
  const [id, name] = l.split('\t'); if (id && name) cityIds[+id] = name.trim();
}
const seen = new Set();
function tzofar() {
  const ws = new WebSocket('wss://ws.tzevaadom.co.il/socket?platform=ANDROID',
    { headers: { Origin: 'https://www.tzevaadom.co.il', 'User-Agent': 'Mozilla/5.0 RedAlertPush' } });
  let alive = setInterval(() => { try { ws.ping(); } catch (_) {} }, 30000);
  ws.on('open', () => { tzofarOpen = true; log('tzofar connected'); });
  ws.on('message', buf => {
    try {
      const m = JSON.parse(buf.toString()); const d = m.data; if (!d) return;
      const id = m.type + '_' + d.notificationId; if (seen.has(id)) return; seen.add(id);
      if (seen.size > 500) seen.delete(seen.values().next().value);
      if (m.type === 'ALERT' && !d.isDrill && THREATS[d.threat ?? 0] && Array.isArray(d.cities)) {
        send(THREATS[d.threat ?? 0], d.cities, 'tzofar');
      } else if (m.type === 'SYSTEM_MESSAGE' && (d.instructionType === 0 || d.instructionType === 1)) {
        const areas = (d.citiesIds || []).map(i => cityIds[i]).filter(Boolean);
        if (areas.length) send(d.instructionType === 0 ? PRE : END, areas, 'tzofar');
      }
    } catch (_) {}
  });
  const again = () => { tzofarOpen = false; clearInterval(alive); setTimeout(tzofar, 5000); };
  ws.on('close', again);
  ws.on('error', () => { try { ws.terminate(); } catch (_) {} });
}
tzofar();

// ---- פיקוד העורף: היסטוריה וארכיון (גיבוי להתרעות שהקובץ החי פספס), כל שנייה ----
const OREF_H = { Referer: 'https://www.oref.org.il/', 'X-Requested-With': 'XMLHttpRequest' };
const HISTORY = [
  ['history', 'https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json'],
  ['archive', 'https://alerts-history.oref.org.il/Shared/Ajax/GetAlarmsHistory.aspx?lang=he&mode=1'],
];
// "2026-10-24 20:10:05" בשעון ישראל -> זמן אמיתי
function ilTime(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(s); if (!m) return 0;
  const asUtc = Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Jerusalem', hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
    .formatToParts(new Date(asUtc)).map(x => [x.type, x.value]));
  const off = Date.UTC(+p.year, p.month - 1, +p.day, +p.hour, +p.minute, +p.second) - asUtc;
  return asUtc - off;
}
const busy = new Set();   // בקשה שעוד לא חזרה - לא שולחים עוד אחת לאותו מקור
const errAt = {};
const fail = (k, e) => { if (Date.now() - (errAt[k] || 0) > 60000) { errAt[k] = Date.now(); log('source error', k, e.message); } };
function pollHistory() {
  for (const [key, url] of HISTORY) (async () => {
    if (busy.has(key)) return;
    busy.add(key);
    try {
      const t = await get(url, OREF_H); ok(key);
      if (!t.startsWith('[')) return;
      const by = new Map();
      for (const o of JSON.parse(t)) {
        const at = ilTime(String(o.alertDate || ''));
        if (!at || Date.now() - at > 90 * 1000) continue;
        const title = o.title || o.category_desc || 'התראה';
        const k = title + '|' + at;
        if (!by.has(k)) by.set(k, { title, at, areas: [] });
        by.get(k).areas.push(o.data);
      }
      for (const e of by.values()) send(e.title, e.areas, key, e.at);
    } catch (e) { fail(key, e); } finally { busy.delete(key); }
  })();
}
setInterval(pollHistory, 1000);

// ---- טלגרם: 4 ערוצים ציבוריים (t.me/s), כל שנייה ----
const CHANNELS = ['PikudHaOref_all', 'tzevaadomm', 'CumtaAlertsChannel', 'Radar_Alerts'];
const lastPost = {};
const decode = h => h.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')
  .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n)).replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
  .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&');
const SECONDS_RE = /\(\s*[\d\s]*(שניות|דקות|מיידי)[^)]*\)/g, DATE_RE = /\(\d{1,2}\/\d{1,2}\/\d{4}\).*$/, ENDED_IN_RE = /הסתיים ב(.+)/;
const skip = l => l.startsWith('אזור') || /מרחב המוגן|השוהים|הסתיים|בדקות הקרובות|\.|היכנסו|בהמשך ל|נעלו|הישארו|\|/.test(l) ||
  l.startsWith('עדכון') || l.startsWith('על תושבי');
function parseTg(text) {
  const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
  if (!lines.length) return null;
  let title;
  if (/הסתיים|הוסר החשש|החשש הוסר|ניתן לצאת/.test(text)) title = END;
  else if (text.includes('בדקות הקרובות')) title = PRE;
  else if (text.includes('כלי טיס')) title = 'חדירת כלי טיס עוין';
  else if (text.includes('מחבלים')) title = 'חדירת מחבלים';
  else if (/צבע אדום|רקטות|אזעקה/.test(text)) title = 'ירי רקטות וטילים';
  else title = lines[0].replace(DATE_RE, '').replace(/[^\p{L}\p{N} '"-]/gu, '').trim();
  if (!title) return null;
  const hasBullets = lines.some(l => l.includes('•'));
  let under = false;
  const areas = lines.flatMap((l, i) => {
    const ended = ENDED_IN_RE.exec(l), bullet = l.includes('•');
    const afterBullet = l.split('•').slice(1).join('•');
    const ci = afterBullet.lastIndexOf(':'), afterColon = ci >= 0 ? afterBullet.slice(ci + 1).trim() : '';
    if (ended) return ended[1].split(',');
    if (bullet && l.includes(' - ')) { under = false; return l.slice(l.indexOf(' - ') + 3).split(','); }
    if (bullet && afterColon) { under = false; return afterColon.split(','); }
    if (bullet) { under = true; return []; }
    if (i > 0 && (!hasBullets || under) && !skip(l)) return l.split(',');
    return [];
  }).map(a => a.replace(SECONDS_RE, '').trim().replace(/\.+$/, '')).filter(a => a && a.length <= 40);
  return areas.length ? { title, areas } : null;
}
async function pollTg(ch) {
  const key = 'tg:' + ch;
  if (busy.has(key)) return;
  busy.add(key);
  try {
    const html = await get('https://t.me/s/' + ch); ok('tg:' + ch);
    const re = new RegExp('data-post="' + ch + '/(\\d+)"', 'gi');
    const posts = [...html.matchAll(re)];
    const parsed = posts.map((m, i) => {
      const end = i + 1 < posts.length ? posts[i + 1].index : html.length;
      const tm = /js-message_text[^>]*>([\s\S]*?)<\/div>/.exec(html.slice(m.index, end));
      return tm ? [+m[1], tm[1]] : null;
    }).filter(Boolean);
    if (!parsed.length) return;
    const newest = Math.max(...parsed.map(p => p[0]));
    if (lastPost[ch] === undefined) { lastPost[ch] = newest; return; }   // טעינה ראשונה: בלי הודעות ישנות
    for (const [id, raw] of parsed.filter(p => p[0] > lastPost[ch]).sort((a, b) => a[0] - b[0])) {
      const r = parseTg(decode(raw));
      if (r) send(r.title, r.areas, 'tg:' + ch);
    }
    lastPost[ch] = Math.max(lastPost[ch], newest);
  } catch (e) { fail(key, e); } finally { busy.delete(key); }
}
setInterval(() => CHANNELS.forEach(pollTg), 1000);


// ---- גרסאות חדשות: השרת בודק את GitHub כל 2 דקות, ושולח לטלפונים הודעה רק כשיש שינוי ----
// (במקום שכל טלפון יתעורר כל חצי שעה לבדוק בעצמו)
const REPO = 'https://github.com/t0548321068/red-alert/releases';
let latest = { stable: '', beta: '' };
function head(url) {
  return new Promise(resolve => {
    const req = https.get(url, { agent: slowAgent, timeout: 8000, headers: { 'User-Agent': 'Mozilla/5.0 RedAlertPush' } }, res => {
      resolve(res.headers.location || ''); res.resume();
    });
    req.on('timeout', () => req.destroy()); req.on('error', () => resolve(''));
  });
}
async function pollVersions() {
  try {
    const loc = await head(REPO + '/latest');
    const stable = (loc.split('/tag/')[1] || '').replace(/^v/, '');
    let beta = '';
    try { const m = /red-alert-([\w.]+)-(\d+)\.apk/.exec(await get(REPO + '/expanded_assets/beta')); if (m) beta = m[1] + '-' + m[2]; } catch (_) {}
    if (!stable && !beta) return;
    const changed = (stable && stable !== latest.stable) || (beta && beta !== latest.beta);
    const first = !latest.stable && !latest.beta;
    latest = { stable: stable || latest.stable, beta: beta || latest.beta };
    if (changed && !first) {
      await admin.messaging().send({ topic: TOPIC, data: { type: 'update', stable: latest.stable, beta: latest.beta },
        android: { priority: 'high', ttl: 6 * 3600 * 1000 } }).catch(e => log('update push failed', e.message));
      log('new version', JSON.stringify(latest));
    }
  } catch (_) {}
}
setInterval(pollVersions, 2 * 60 * 1000);
setTimeout(pollVersions, 5000);

// ---- התרעות אחרונות: השרת שומר את כל ההיסטוריה של פיקוד העורף (שנה אחורה), והטלפונים מקבלים ממנו קובץ אחד ----
// כך לכל הטלפונים אותה רשימה, בלי תקרת השורות של הארכיון
const ARCH_FILE = path.join(__dirname, 'archive.json');
const ARCH_BASE = 'https://alerts-history.oref.org.il/Shared/Ajax/GetAlarmsHistory.aspx?lang=he';
const YEAR = 366 * 24 * 3600 * 1000;
let arch = new Map();   // "כותרת|דקה" -> { t, ts, b: Set }
try { for (const e of JSON.parse(fs.readFileSync(ARCH_FILE, 'utf8'))) arch.set(e.k, { t: e.t, ts: e.ts, b: new Set(e.b) }); } catch (_) {}
function rowTime(o) {
  const d = String(o.date || ''), t = String(o.time || '');
  const m = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(d);
  if (m && t.length >= 8) return ilTime(`${m[3]}-${m[2]}-${m[1]} ${t.slice(0, 8)}`);
  return ilTime(String(o.alertDate || '').replace('T', ' '));
}
function addRows(rows) {
  let added = 0;
  for (const o of rows) {
    const title = o.category_desc || o.title || 'התראה';
    const minute = String(o.alertDate || '').replace('T', ' ').slice(0, 16);
    const ts = rowTime(o); if (!ts || !minute) continue;
    const k = title + '|' + minute;
    let e = arch.get(k); if (!e) { e = { t: title, ts, b: new Set() }; arch.set(k, e); added++; }
    if (ts < e.ts) e.ts = ts;
    if (o.data && !e.b.has(o.data)) { e.b.add(o.data); added++; }
  }
  return added;
}
function saveArch() {
  const cut = Date.now() - YEAR;
  for (const [k, e] of arch) if (e.ts < cut) arch.delete(k);
  try { fs.writeFileSync(ARCH_FILE + '.tmp', JSON.stringify([...arch].map(([k, e]) => ({ k, t: e.t, ts: e.ts, b: [...e.b] })))); fs.renameSync(ARCH_FILE + '.tmp', ARCH_FILE); } catch (_) {}
}
async function archFetch(q) { const t = await get(ARCH_BASE + q, OREF_H); return t.startsWith('[') ? JSON.parse(t) : []; }
async function archBackfill() {
  // פעם אחת (ואחר כך כשחסר): החודש האחרון, ושנה אחורה בטווחים של שבוע
  const fmt = d => { const p = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Jerusalem', day: '2-digit', month: '2-digit', year: 'numeric' }).formatToParts(d); const g = x => p.find(y => y.type === x).value; return `${g('day')}.${g('month')}.${g('year')}`; };
  try { addRows(await archFetch('&mode=3')); } catch (_) {}
  const now = Date.now(), DAY = 24 * 3600 * 1000;
  for (let to = now - 28 * DAY; to > now - YEAR; to -= 7 * DAY) {
    try { addRows(await archFetch(`&fromDate=${fmt(new Date(to - 6 * DAY))}&toDate=${fmt(new Date(to))}&mode=0`)); } catch (_) {}
    await new Promise(r => setTimeout(r, 1500));
  }
  saveArch(); log('archive backfill', arch.size + ' events');
}
async function archPoll() {
  try { if (addRows(await archFetch('&mode=1'))) saveArch(); } catch (_) {}
}
if (arch.size < 100) archBackfill(); else archPoll();
setInterval(archPoll, 60 * 1000);
function serveArchive(req, res, since) {
  const list = [...arch.values()].filter(e => e.ts > since).sort((a, b) => b.ts - a.ts)
    .map(e => ({ t: e.t, b: [...e.b].join(', '), ts: e.ts }));
  const body = Buffer.from(JSON.stringify(list));
  const gz = /gzip/.test(req.headers['accept-encoding'] || '');
  res.writeHead(200, { 'Content-Type': 'application/json', ...(gz ? { 'Content-Encoding': 'gzip' } : {}) });
  res.end(gz ? require('zlib').gzipSync(body) : body);
}

// ---- מזג אוויר מ-OpenWeather: המפתח רק כאן בשרת (הקובץ openweather.key, לא נכנס לגיט) ----
// הקוד מתורגם לקודי WMO שהאפליקציה מכירה (אותם אייקונים כמו קודם). שמור 5 דקות לכל ~1 ק"מ
const wxCache = new Map();
function wmo(id) {
  if (id >= 200 && id < 300) return 95;
  if (id >= 300 && id < 400) return 51;
  if (id === 511) return 66;
  if (id >= 520 && id < 600) return 80;
  if (id >= 500 && id < 600) return 61;
  if (id >= 600 && id < 700) return 71;
  if (id >= 700 && id < 800) return 45;
  if (id === 800) return 0;
  if (id === 801) return 1;
  if (id === 802) return 2;
  return 3;
}
async function serveWeather(res, lat, lon) {
  const fail = c => { res.writeHead(c); res.end(); };
  let key = '';
  try { key = fs.readFileSync(path.join(__dirname, 'openweather.key'), 'utf8').trim(); } catch (_) {}
  if (!key || !isFinite(lat) || !isFinite(lon)) return fail(503);
  const ck = lat.toFixed(3) + ',' + lon.toFixed(3);   // ~100 מטר - שם השכונה נשאר מדויק
  const hit = wxCache.get(ck);
  if (hit && Date.now() - hit.at < 5 * 60 * 1000) { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(hit.body); }
  try {
    const j = JSON.parse(await get(`https://api.openweathermap.org/data/2.5/weather?lat=${lat}&lon=${lon}&units=metric&lang=he&appid=${encodeURIComponent(key)}`));
    const w = (j.weather || [])[0] || {};
    const place = await placeName(lat, lon);
    const body = JSON.stringify({ temp: Math.round(j.main.temp), code: wmo(w.id || 800), isDay: String(w.icon || 'd').endsWith('d'), desc: w.description || '', place });
    wxCache.set(ck, { at: Date.now(), body });
    if (wxCache.size > 500) wxCache.delete(wxCache.keys().next().value);
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(body);
  } catch (e) { log('weather failed', e.message); fail(502); }
}

// ---- שם המקום ("שכונה, עיר") - מ-OpenStreetMap, שמור לפי ~100 מטר (פחות בקשות, מהיר יותר) ----
const placeCache = new Map();
async function placeName(lat, lon) {
  const k = lat.toFixed(3) + ',' + lon.toFixed(3);
  if (placeCache.has(k)) return placeCache.get(k);
  let name = '';
  try {
    const a = (JSON.parse(await get(`https://nominatim.openstreetmap.org/reverse?format=json&zoom=18&lat=${lat}&lon=${lon}&accept-language=he`,
      { 'User-Agent': 'TzofarHerumServer/1.0 (t0548321068 red-alert)' })).address) || {};
    const city = ['city', 'town', 'village', 'municipality'].map(x => a[x]).find(x => x && !x.startsWith('מועצה אזורית'));   // מועצה אזורית = השטח שמסביב, לא המקום
    const hood = ['neighbourhood', 'suburb', 'quarter', 'residential'].map(x => a[x]).find(x => x && x !== city);
    name = [hood, city].filter(Boolean).join(', ');
  } catch (_) {}
  if (name) { placeCache.set(k, name); if (placeCache.size > 5000) placeCache.delete(placeCache.keys().next().value); }
  return name;
}

// ---- זמני שבת וחג: אותו חישוב כמו באפליקציה (עתים לבינה: 162 יישובים עם מנהג המקום), במקום אחד ----
// כך תיקון זמנים נעשה בשרת, בלי לעדכן את האפליקציה. מחזיר את השבתות והחגים של החודשיים הקרובים
const SH_CITIES = JSON.parse(fs.readFileSync(path.join(__dirname, 'shabbat_cities.json'), 'utf8'));
function shNearest(lat, lon) {
  const k = Math.cos(lat * Math.PI / 180); let best = SH_CITIES[0], bd = Infinity;
  for (const c of SH_CITIES) { const dy = c[0] - lat, dx = (c[1] - lon) * k, d = dy * dy + dx * dx; if (d < bd) { bd = d; best = c; } }
  return best;
}
const ilParts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Jerusalem', year: 'numeric', month: 'numeric', day: 'numeric', weekday: 'short' });
const hebParts = new Intl.DateTimeFormat('en-u-ca-hebrew', { timeZone: 'Asia/Jerusalem', month: 'long', day: 'numeric' });
function ilDay(ms) { const p = Object.fromEntries(ilParts.formatToParts(new Date(ms)).map(x => [x.type, x.value])); return { y: +p.year, m: +p.month, d: +p.day, wd: p.weekday }; }
function isHoly(ms) {   // ms = צהרי היום
  if (ilDay(ms).wd === 'Sat') return true;
  const p = Object.fromEntries(hebParts.formatToParts(new Date(ms)).map(x => [x.type, x.value]));
  const m = String(p.month || ''), d = +p.day;
  if (/^Tishri/.test(m)) return [1, 2, 10, 15, 22].includes(d);
  if (/^Nisan/.test(m)) return d === 15 || d === 21;
  if (/^Sivan/.test(m)) return d === 6;
  return false;
}
function sunsetUtc(y, mo, d, lat, lon, zenith) {   // NOAA - כמו באפליקציה
  const R = Math.PI / 180;
  const n = Math.round((Date.UTC(y, mo - 1, d) - Date.UTC(y, 0, 0)) / 86400000);
  const lngHour = lon / 15, t = n + ((18 - lngHour) / 24), m = 0.9856 * t - 3.289;
  let l = m + 1.916 * Math.sin(m * R) + 0.020 * Math.sin(2 * m * R) + 282.634; l = (l + 360) % 360;
  let ra = Math.atan(0.91764 * Math.tan(l * R)) / R; ra = (ra + 360) % 360;
  ra += Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90; ra /= 15;
  const sinDec = 0.39782 * Math.sin(l * R), cosDec = Math.cos(Math.asin(sinDec));
  const cosH = (Math.cos(zenith * R) - sinDec * Math.sin(lat * R)) / (cosDec * Math.cos(lat * R));
  const h = Math.acos(Math.max(-1, Math.min(1, cosH))) / R / 15;
  const ut = (((h + ra - 0.06571 * t - 6.622) - lngHour) % 24 + 24) % 24;
  return Date.UTC(y, mo - 1, d) + ut * 3600000;
}
const roundMin = ms => Math.floor((ms + 30000) / 60000) * 60000;
function shabbatWindows(lat, lon) {
  const c = shNearest(lat, lon), out = [], DAY = 86400000;
  const noon = t => { const x = ilDay(t); return Date.UTC(x.y, x.m - 1, x.d, 10); };   // ~12:00 בישראל
  let day = noon(Date.now() - DAY);
  for (let i = 0; i < 62 && out.length < 12; i++, day += DAY) {
    if (!isHoly(day) || isHoly(day - DAY)) continue;
    const p = ilDay(day - DAY);
    const start = roundMin(sunsetUtc(p.y, p.m, p.d, lat, lon, 90.833) + c[2] * 60000);
    let last = day; while (isHoly(last + DAY)) last += DAY;
    const q = ilDay(last);
    const end = roundMin(sunsetUtc(q.y, q.m, q.d, lat, lon, 98.5) + c[3] * 60000);
    if (end > Date.now()) out.push({ in: start, out: end });
  }
  return out;
}
function serveShabbat(res, lat, lon) {
  if (!isFinite(lat) || !isFinite(lon)) { res.writeHead(400); return res.end(); }
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ windows: shabbatWindows(lat, lon), at: Date.now() }));
}

// ---- התראה לך (רק למכשירי הבטא - נושא admin) כשמקור לא תקין יותר מ-10 דקות, וכשהוא חוזר ----
const ALL_SOURCES = ['oref', 'history', 'archive', 'tzofar', 'tg:PikudHaOref_all', 'tg:tzevaadomm', 'tg:CumtaAlertsChannel', 'tg:Radar_Alerts'];
const SRC_NAMES = { oref: 'פיקוד העורף – חי', history: 'פיקוד העורף – היסטוריה', archive: 'פיקוד העורף – ארכיון', tzofar: 'צופר',
  'tg:PikudHaOref_all': 'טלגרם – פיקוד העורף', 'tg:tzevaadomm': 'טלגרם – צופר', 'tg:CumtaAlertsChannel': 'טלגרם – כומתה', 'tg:Radar_Alerts': 'טלגרם – רדאר' };
const downSince = {}, alerted = {};
setInterval(async () => {
  if (Date.now() - STARTED < 5 * 60 * 1000) return;   // אחרי הפעלה מחדש - זמן להתחבר
  const up = new Set(upList());
  for (const k of ALL_SOURCES) {
    if (up.has(k)) {
      if (alerted[k]) { alerted[k] = false; adminPush(`✅ ${SRC_NAMES[k]} חזר לעבוד`); }
      delete downSince[k];
    } else {
      downSince[k] = downSince[k] || Date.now();
      if (!alerted[k] && Date.now() - downSince[k] > 10 * 60 * 1000) { alerted[k] = true; adminPush(`⚠️ ${SRC_NAMES[k]} לא עונה לשרת כבר 10 דקות`); }
    }
  }
}, 60 * 1000);
function adminPush(text) {
  log('admin', text);
  admin.messaging().send({ topic: 'admin', data: { type: 'admin', text }, android: { priority: 'high', ttl: 3600 * 1000 } })
    .catch(e => log('admin push failed', e.message));
}
log('red-alert push server started');
