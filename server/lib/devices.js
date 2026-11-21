// המכשירים והקריסות - לאתר בלבד (רק לך).
// /ping   כל טלפון מדווח פעם ב-10 דקות (עם סימן החיים): גרסה, דגם, מוגן או לא
// /crash  האפליקציה קרסה בטלפון - השגיאה נשמרת ונשלחת אליך כהודעת תקלה
// /report דיווח על בעיה ממסך "יצירת קשר" באפליקציה - נשמר ונשלח רק לאפליקציית "פניות צופר" (נושא inbox)
// אין כאן שום מידע אישי: מזהה מכשיר מקוצר (אותו קוד של הבטא), דגם וגרסה
const fs = require('fs');
const path = require('path');
const { log } = require('./util');
// push נטען כאן מאוחר: push → web → devices → push (טעינה מעגלית)
const adminPush = t => require('./push').adminPush(t);

const FILE = path.join(__dirname, '..', 'devices.json');
let data = { devices: {}, crashes: [], messages: [], reports: [] };
try { data = Object.assign(data, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (_) {}
const newId = () => require('crypto').randomBytes(4).toString('hex');
// פניות ישנות (לפני פניות צופר): מזהה, וסימון שנקראו
for (const r of data.reports) { if (!r.id) { r.id = newId(); r.read = true; } if (!r.replies) r.replies = []; }
let saveT = null;
function save() {
  clearTimeout(saveT);
  saveT = setTimeout(() => { saveT = null; fs.writeFile(FILE + '.tmp', JSON.stringify(data), e => { if (!e) fs.rename(FILE + '.tmp', FILE, () => {}); }); }, 2000);
}
// כתיבה מיידית לפני יציאה - שינוי שעוד מחכה לשמירה לא ילך לאיבוד
function saveNow() {
  if (!saveT) return;
  clearTimeout(saveT); saveT = null;
  try { fs.writeFileSync(FILE + '.exit.tmp', JSON.stringify(data)); fs.renameSync(FILE + '.exit.tmp', FILE); } catch (e) { log('devices save failed', e.message); }
}

// הודעות תקלה מקריסות ודיווחים: עד 10 ב-10 דקות בסך הכול (שקודים מומצאים לא יציפו את הטלפונים). מעבר לזה - רק נשמר
let pushTimes = [];
function limitedPush(t) {
  const now = Date.now();
  pushTimes = pushTimes.filter(x => now - x < 10 * 60 * 1000);
  if (pushTimes.length >= 10) return;
  pushTimes.push(now); adminPush(t);
}

const clean = (v, n) => String(v || '').replace(/[\u0000-\u001f]/g, ' ').slice(0, n);
const cleanLines = (v, n) => String(v || '').replace(/[\u0000-\u0008\u000b-\u001f]/g, ' ').slice(0, n);   // שומר שורות (השגיאה)
const okCode = c => /^[0-9A-F]{8}$/.test(c);

function ping(res, q) {
  const code = String(q('d') || '').toUpperCase();
  if (!okCode(code)) { res.writeHead(400); return res.end(); }
  if (!data.devices[code]) {
    // מכשיר שלא דיווח 30 יום - יוצא מהרשימה; אם עדיין מלאה - הוותיק ביותר יוצא
    const now = Date.now(), list = data.devices;
    for (const [d, x] of Object.entries(list)) if (now - (x.at || 0) > 30 * 24 * 3600 * 1000) delete list[d];
    if (Object.keys(list).length >= 50) {
      const oldest = Object.keys(list).reduce((a, b) => (list[a].at || 0) <= (list[b].at || 0) ? a : b);
      delete list[oldest];
    }
  }
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
    limitedPush(`💥 קריסה ב-${c.m || code} (גרסה ${c.v}): ${first}`);
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
    const r = { id: newId(), d: code, v: clean(j.v, 16), m: clean(j.m, 40), at: now, text, info: cleanLines(j.info, 3000), read: false, replies: [] };
    data.reports.unshift(r);
    data.reports.length = Math.min(data.reports.length, 200);
    save();
    log('report', code, text.slice(0, 80));
    inboxPush(r);
    res.writeHead(204); res.end();
  });
}

// ניקוי מכשירים ישנים מהמגבלות (אחרת קודים מומצאים ממלאים את הזיכרון)
setInterval(() => {
  const now = Date.now();
  for (const [c, t] of crashAt) if (now - t > 60 * 1000) crashAt.delete(c);
  for (const [c, ts] of reportAt) if (!ts.some(t => now - t < 10 * 60 * 1000)) reportAt.delete(c);
}, 60 * 1000);

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

// ---- פניות צופר: הפניות מ"יצירת קשר" - רק לאפליקציה הזו (לא לכל טלפוני הבטא) ----
// עד 10 התראות ב-10 דקות (שקודים מומצאים לא יציפו). מעבר לזה - רק נשמר
let inboxTimes = [];
function inboxPush(r) {
  const now = Date.now();
  inboxTimes = inboxTimes.filter(x => now - x < 10 * 60 * 1000);
  if (inboxTimes.length >= 10) return;
  inboxTimes.push(now);
  require('./push').inbox(r.id, r.m || r.d, r.text.replace(/\s+/g, ' ').slice(0, 200));
}

/** GET: כל הפניות (הכי חדשה למעלה). POST {id, reply} תשובה לפונה · {id, read} סימון · {id, del} מחיקה */
function inbox(req, res) {
  const out = (code, j) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(j)); };
  if (req.method !== 'POST') return out(200, { reports: data.reports });
  let body = '';
  req.on('data', d => { body += d; if (body.length > 4000) req.destroy(); });
  req.on('end', async () => {
    let j = {};
    try { j = JSON.parse(body); } catch (_) {}
    const r = data.reports.find(x => x.id === String(j.id || ''));
    if (!r) return out(404, { error: 'not found' });
    if (j.del) { data.reports = data.reports.filter(x => x !== r); save(); return out(200, { ok: true }); }
    if (typeof j.read === 'boolean') { r.read = j.read; save(); return out(200, { ok: true }); }
    const text = clean(j.reply, 500).trim();
    if (!text) return out(400, { error: 'empty' });
    try {
      await require('./push').message(text, r.d);
      r.replies.push({ text, at: Date.now() }); r.read = true;
      save();
      out(200, { ok: true, report: r });
    } catch (e) { log('reply failed', e.message); out(502, { error: 'send failed' }); }
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

module.exports = { ping, crash, report, inbox, list, sendMessage, saveNow };
