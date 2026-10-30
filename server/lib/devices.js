// המכשירים והקריסות - לאתר בלבד (רק לך).
// /ping   כל טלפון מדווח פעם ב-10 דקות (עם סימן החיים): גרסה, דגם, מוגן או לא
// /crash  האפליקציה קרסה בטלפון - השגיאה נשמרת ונשלחת אליך כהודעת תקלה
// /report דיווח על בעיה ממסך "יצירת קשר" באפליקציה
// אין כאן שום מידע אישי: מזהה מכשיר מקוצר (אותו קוד של הבטא), דגם וגרסה
const fs = require('fs');
const path = require('path');
const { log } = require('./util');
// push נטען כאן מאוחר: push → web → devices → push (טעינה מעגלית)
const adminPush = t => require('./push').adminPush(t);

const FILE = path.join(__dirname, '..', 'devices.json');
let data = { devices: {}, crashes: [], messages: [], reports: [] };
try { data = Object.assign(data, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (_) {}
let saveT = null;
function save() {
  clearTimeout(saveT);
  saveT = setTimeout(() => fs.writeFile(FILE + '.tmp', JSON.stringify(data), e => { if (!e) fs.rename(FILE + '.tmp', FILE, () => {}); }), 2000);
}

const clean = (v, n) => String(v || '').replace(/[\u0000-\u001f]/g, ' ').slice(0, n);
const cleanLines = (v, n) => String(v || '').replace(/[\u0000-\u0008\u000b-\u001f]/g, ' ').slice(0, n);   // שומר שורות (השגיאה)
const okCode = c => /^[0-9A-F]{8}$/.test(c);

function ping(res, q) {
  const code = String(q('d') || '').toUpperCase();
  if (!okCode(code)) { res.writeHead(400); return res.end(); }
  if (!data.devices[code] && Object.keys(data.devices).length >= 50) { res.writeHead(429); return res.end(); }
  data.devices[code] = { v: clean(q('v'), 16), m: clean(q('m'), 40), on: q('on') === '1', at: Date.now() };
  save();
  res.writeHead(204); res.end();
}

const crashAt = new Map();   // לכל מכשיר - קריסה אחת בדקה לכל היותר
function crash(req, res) {
  let body = '';
  req.on('data', d => { body += d; if (body.length > 20000) req.destroy(); });
  req.on('end', () => {
    let j = {};
    try { j = JSON.parse(body); } catch (_) {}
    const code = String(j.d || '').toUpperCase();
    if (!okCode(code)) { res.writeHead(400); return res.end(); }
    if (Date.now() - (crashAt.get(code) || 0) < 60 * 1000) { res.writeHead(429); return res.end(); }
    crashAt.set(code, Date.now());
    const c = { d: code, v: clean(j.v, 16), m: clean(j.m, 40), at: +j.at || Date.now(), got: Date.now(), err: cleanLines(j.err, 12000) };
    data.crashes.unshift(c);
    data.crashes.length = Math.min(data.crashes.length, 50);
    save();
    const first = (c.err.split('\n')[0] || '').slice(0, 120);
    log('crash', code, c.v, first);
    adminPush(`💥 קריסה ב-${c.m || code} (גרסה ${c.v}): ${first}`);
    res.writeHead(204); res.end();
  });
}

// ---- דיווח על בעיה ממסך "יצירת קשר" באפליקציה ----
const reportAt = new Map();   // לכל מכשיר - עד 3 דיווחים ב-10 דקות
function report(req, res) {
  let body = '';
  req.on('data', d => { body += d; if (body.length > 12000) req.destroy(); });
  req.on('end', () => {
    let j = {};
    try { j = JSON.parse(body); } catch (_) {}
    const code = String(j.d || '').toUpperCase(), text = cleanLines(j.text, 1500).trim();
    if (!okCode(code) || !text) { res.writeHead(400); return res.end(); }
    const now = Date.now(), times = (reportAt.get(code) || []).filter(t => now - t < 10 * 60 * 1000);
    if (times.length >= 3) { res.writeHead(429); return res.end(); }
    times.push(now); reportAt.set(code, times);
    const r = { d: code, v: clean(j.v, 16), m: clean(j.m, 40), at: now, text, info: cleanLines(j.info, 3000) };
    data.reports.unshift(r);
    data.reports.length = Math.min(data.reports.length, 50);
    save();
    log('report', code, text.slice(0, 80));
    adminPush(`📩 דיווח מ-${r.m || code}: ${text.replace(/\s+/g, ' ').slice(0, 120)}`);
    res.writeHead(204); res.end();
  });
}

// ---- הודעה מהאתר לטלפונים ----
function sendMessage(req, res) {
  let body = '';
  req.on('data', d => { body += d; if (body.length > 4000) req.destroy(); });
  req.on('end', async () => {
    let j = {};
    try { j = JSON.parse(body); } catch (_) {}
    const text = clean(j.text, 500).trim(), to = String(j.to || '').toUpperCase() === 'ALL' ? 'all' : String(j.to || '').toUpperCase();
    if (!text || (to !== 'all' && !okCode(to))) { res.writeHead(400); return res.end(); }
    try {
      await require('./push').message(text, to);
      data.messages.unshift({ text, to, at: Date.now() });
      data.messages.length = Math.min(data.messages.length, 20);
      save();
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"ok":true}');
    } catch (e) { log('message failed', e.message); res.writeHead(502); res.end(); }
  });
}

/** לאתר: המכשירים (הכי חדש למעלה) והקריסות האחרונות */
function list() {
  return {
    devices: Object.entries(data.devices).map(([d, x]) => Object.assign({ d }, x)).sort((a, b) => b.at - a.at),
    crashes: data.crashes,
    messages: data.messages,
    reports: data.reports,
  };
}

module.exports = { ping, crash, report, list, sendMessage };
