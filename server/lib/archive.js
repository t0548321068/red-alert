// התרעות אחרונות: השרת שומר את כל ההיסטוריה של פיקוד העורף (שנה אחורה), והטלפונים מקבלים ממנו קובץ אחד.
// כך לכל הטלפונים אותה רשימה, בלי תקרת השורות של הארכיון
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { get, ilTime, log } = require('./util');

const OREF_H = { Referer: 'https://www.oref.org.il/', 'X-Requested-With': 'XMLHttpRequest' };
const ARCH_FILE = path.join(__dirname, '..', 'archive.json');
const ARCH_BASE = 'https://alerts-history.oref.org.il/Shared/Ajax/GetAlarmsHistory.aspx?lang=he';
const YEAR = 366 * 24 * 3600 * 1000;
const arch = new Map();   // "כותרת|דקה" -> { t, ts, b: Set }
try { for (const e of JSON.parse(fs.readFileSync(ARCH_FILE, 'utf8'))) arch.set(e.k, { t: e.t, ts: e.ts, b: new Set(e.b) }); } catch (_) {}

// הרשימה המוכנה לשליחה - נבנית מחדש רק כשהארכיון השתנה (לא בכל בקשה)
let cache = null;
function sorted() {
  if (!cache) {
    const list = [...arch.values()].sort((a, b) => b.ts - a.ts).map(e => ({ t: e.t, b: [...e.b].join(', '), ts: e.ts }));
    const body = Buffer.from(JSON.stringify(list));
    cache = { list, body, gz: zlib.gzipSync(body) };
  }
  return cache;
}

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
    if (ts < e.ts) { e.ts = ts; added++; }
    if (o.data && !e.b.has(o.data)) { e.b.add(o.data); added++; }
  }
  if (added) cache = null;
  return added;
}
// שמירה אחת בכל פעם: שתי כתיבות במקביל לאותו קובץ זמני (כמה MB) משחיתות אותו. בקשה בזמן שמירה - נשמרת פעם אחת אחריה
let saving = false, pending = false;
function save() {
  if (saving) { pending = true; return; }
  saving = true;
  const cut = Date.now() - YEAR;
  for (const [k, e] of arch) if (e.ts < cut) { arch.delete(k); cache = null; }
  const data = JSON.stringify([...arch].map(([k, e]) => ({ k, t: e.t, ts: e.ts, b: [...e.b] })));
  const done = () => { saving = false; if (pending) { pending = false; save(); } };
  fs.writeFile(ARCH_FILE + '.tmp', data, err => { if (err) return done(); fs.rename(ARCH_FILE + '.tmp', ARCH_FILE, done); });
}
async function fetchRows(q) { const t = await get(ARCH_BASE + q, OREF_H); return t.startsWith('[') ? JSON.parse(t) : []; }
async function backfill() {
  // פעם אחת (ואחר כך כשחסר): החודש האחרון, ושנה אחורה בטווחים של שבוע
  const fmt = d => { const p = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Jerusalem', day: '2-digit', month: '2-digit', year: 'numeric' }).formatToParts(d); const g = x => p.find(y => y.type === x).value; return `${g('day')}.${g('month')}.${g('year')}`; };
  try { addRows(await fetchRows('&mode=3')); } catch (_) {}
  const now = Date.now(), DAY = 24 * 3600 * 1000;
  for (let to = now - 28 * DAY; to > now - YEAR; to -= 7 * DAY) {
    try { addRows(await fetchRows(`&fromDate=${fmt(new Date(to - 6 * DAY))}&toDate=${fmt(new Date(to))}&mode=0`)); } catch (_) {}
    await new Promise(r => setTimeout(r, 1500));
  }
  save(); log('archive backfill', arch.size + ' events');
}
async function poll() { try { if (addRows(await fetchRows('&mode=1'))) save(); } catch (_) {} }

function serve(req, res, since) {
  const c = sorted();
  const gz = /gzip/.test(req.headers['accept-encoding'] || '');
  let body, zipped;
  if (since > 0) {   // רק מה שחדש מאז הבדיקה הקודמת - בדרך כלל מעט
    body = Buffer.from(JSON.stringify(c.list.filter(e => e.ts > since)));
    zipped = gz ? zlib.gzipSync(body) : null;
  } else { body = c.body; zipped = gz ? c.gz : null; }
  res.writeHead(200, { 'Content-Type': 'application/json', ...(gz ? { 'Content-Encoding': 'gzip' } : {}) });
  res.end(gz ? zipped : body);
}

function start() {
  if (arch.size < 100) backfill(); else poll();
  setInterval(poll, 60 * 1000);
}

module.exports = { start, serve };
