// התרעות אחרונות: השרת שומר את כל ההיסטוריה של פיקוד העורף (שנה אחורה), והטלפונים מקבלים ממנו קובץ אחד.
// כך לכל הטלפונים אותה רשימה, בלי תקרת השורות של הארכיון
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { get, ilTime, log, OREF_HEADERS } = require('./util');

const OREF_H = OREF_HEADERS;
const ARCH_FILE = path.join(__dirname, '..', 'archive.json');
// הארכיון החדש של פיקוד העורף (נובמבר 2026; alerts-history הישן מפנה לדף האתר). period: lastDay / lastWeek / lastMonth
const ARCH_BASE = 'https://www.oref.org.il/api/v1/AlertHistory/heb?period=';
// התשובה החדשה (מקובצת לפי תאריך) -> שורות בפורמט הישן: data, category_desc, alertDate עם שניות, date, time
const convert = require('./parse').archiveRows;   // נבדק בבדיקות
const YEAR = 366 * 24 * 3600 * 1000;
const arch = new Map();   // "כותרת|דקה" -> { t, ts, b: Set, a: { יישוב: מתי הגיע } }
try { for (const e of JSON.parse(fs.readFileSync(ARCH_FILE, 'utf8'))) arch.set(e.k, { t: e.t, ts: e.ts, b: new Set(e.b), a: e.a || {} }); } catch (_) {}
// תיקון חד-פעמי (11/11/2026): רעידת האדמה וההנחיות של היום נשמרו לפני ששמירת סדר ההגעה פעלה - הסדר והיישובים כמו שהגיעו.
// אידמפוטנטי; אפשר למחוק אחרי שעבר יום
(function fixQuake() {
  const fix = [
    ['רעידת אדמה|2026-11-11 11:25', [['בחלק מהאזורים בארץ', 2], ['אילת', 4], ['אזור תעשייה שחורת', 4], ['אילות', 4], ['אליפז ומכרות תמנע', 4], ['באר אורה', 4]],
      ['אילת', 'אזור תעשייה שחורת', 'אילות', 'אליפז ומכרות תמנע', 'באר אורה', 'בחלק מהאזורים בארץ']],
    ['רעידת אדמה - הנחיות חזרה למבנים|2026-11-11 12:14', [['אליפז ומכרות תמנע', 0], ['באר אורה', 0], ['אילות', 0], ['אילת', 0], ['אזור תעשייה שחורת', 0]],
      ['אליפז ומכרות תמנע', 'באר אורה', 'אילות', 'אילת', 'אזור תעשייה שחורת']],
  ];
  for (const [k, times, order] of fix) {
    const e = arch.get(k); if (!e) continue;
    e.b = new Set([...order, ...[...e.b].filter(x => !order.includes(x))]);
    e.a = e.a || {}; for (const [n, sec] of times) e.a[n] = e.ts + sec * 1000;
    e.nl = order.length;
  }
})();
const setTime = (e, area, ts) => { if (!e.a) e.a = {}; if (!(e.a[area] <= ts)) { e.a[area] = ts; return 1; } return 0; };

// הרשימה המוכנה לשליחה - נבנית מחדש רק כשהארכיון השתנה (לא בכל בקשה)
let cache = null;
function sorted() {
  if (!cache) {
    // s: לכל יישוב (באותו סדר כמו b) - כמה שניות אחרי תחילת השורה הוא הגיע
    const list = [...arch.values()].sort((a, b) => b.ts - a.ts).map(e => {
      const b = [...e.b];
      return { t: e.t, b: b.join(', '), ts: e.ts, s: b.map(x => e.a && e.a[x] ? Math.max(0, Math.round((e.a[x] - e.ts) / 1000)) : 0) };
    });
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
    let e = arch.get(k); if (!e) { e = { t: title, ts, b: new Set(), a: {} }; arch.set(k, e); added++; }
    if (ts < e.ts) { e.ts = ts; added++; }
    if (o.data && !e.b.has(o.data)) { e.b.add(o.data); added++; }
    if (o.data) added += setTime(e, o.data, ts);
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
  const data = JSON.stringify([...arch].map(([k, e]) => ({ k, t: e.t, ts: e.ts, b: [...e.b], a: e.a || {} })));
  const done = () => { saving = false; if (pending) { pending = false; save(); } };
  fs.writeFile(ARCH_FILE + '.tmp', data, err => { if (err) return done(); fs.rename(ARCH_FILE + '.tmp', ARCH_FILE, done); });
}
async function fetchRows(period) { return convert(await get(ARCH_BASE + period, OREF_H, { localAddress: require('./egress').current() })) || []; }
async function backfill() {
  // הארכיון החדש מחזיר עד חודש אחורה - מה שנשמר מלפני כן נשאר בקובץ
  try { addRows(await fetchRows('lastMonth')); } catch (_) {}
  save(); log('archive backfill', arch.size + ' events');
}
// התרעות שנשלחו בזמן אמת: יישובים שהגיעו בזמן אמת ולא נכנסו לארכיון של פיקוד העורף (למשל רעידת אדמה:
// "בחלק מהאזורים בארץ" בארכיון, ואילת והערבה רק בזמן אמת) מצטרפים לשורה של אותו סוג באותו זמן (עד דקה וחצי).
// אין שורה כזו בארכיון (עדיין) - מחכים לה עד 10 דקות; כותרת אחרת (כמו "האירוע הסתיים" במקום הנוסח המלא) לא יוצרת שורה
const LIVE_WAIT = 10 * 60 * 1000, LIVE_NEAR = 90 * 1000;
let live = [];
function joinLive() {
  if (!live.length) return 0;
  const now = Date.now();
  let added = 0;
  live = live.filter(l => {
    let best = null;
    for (const e of arch.values()) if (e.t === l.t && Math.abs(e.ts - l.at) <= LIVE_NEAR && (!best || Math.abs(e.ts - l.at) < Math.abs(best.ts - l.at))) best = e;
    if (!best) return now - l.at < LIVE_WAIT;
    // הסדר - כמו שהגיעו בזמן אמת (קודם היישובים שהגיעו בזמן אמת, אחריהם מה שיש רק בארכיון)
    const arr = [...best.b], nl = best.nl || 0, head = arr.slice(0, nl);
    const add = l.areas.filter(a => !head.includes(a));
    const order = [...head, ...add, ...arr.slice(nl).filter(a => !add.includes(a))];
    if (order.join('|') !== arr.join('|')) { best.b = new Set(order); added++; }
    for (const a of l.areas) added += setTime(best, a, l.at);
    best.nl = head.length + add.length;
    return false;
  });
  if (added) cache = null;
  return added;
}
function addLive(title, areas, at) {
  try { live.push({ t: title, areas, at: at || Date.now() }); if (joinLive()) save(); } catch (_) {}
}
/** שורות שהגיעו ממקור אחר (הממסר בבית) */
function ingest(rows) { try { if (addRows(rows) + joinLive()) save(); } catch (_) {} }
async function poll() { try { if (addRows(await fetchRows('lastDay')) + joinLive()) save(); } catch (_) {} }

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

module.exports = { start, serve, convert, ARCH_BASE, ingest, addLive };
