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
  for (const part of chunks) {
    try {
      await admin.messaging().send({
        topic: TOPIC,
        data: { type: 'alert', title, areas: part.join('|'), src, at: String(at) },
        android: { priority: 'high', ttl: 60 * 1000 },
      });
    } catch (e) { log('send failed', e.message); }
  }
  log('sent', src, title, areas.length + ' areas', chunks.length + ' msg');
}

// ---- מצב המקורות (נשלח בסימן החיים, והטלפון מציג אותו) ----
const okAt = {};
const ok = k => { okAt[k] = Date.now(); };
let tzofarOpen = false;
async function heartbeat() {
  const up = Object.keys(okAt).filter(k => Date.now() - okAt[k] < 5 * 60 * 1000);
  if (tzofarOpen && !up.includes('tzofar')) up.push('tzofar');
  try {
    await admin.messaging().send({
      topic: TOPIC, data: { type: 'hb', up: up.join(','), at: String(Date.now()) },
      android: { priority: 'high', ttl: 5 * 60 * 1000 },
    });
  } catch (e) { log('hb failed', e.message); }
}
setInterval(heartbeat, 10 * 60 * 1000);
setTimeout(heartbeat, 20 * 1000);

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

log('red-alert push server started');
