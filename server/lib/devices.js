// המכשירים והקריסות - לאתר בלבד (רק לך).
// /ping   כל טלפון מדווח פעם ב-10 דקות (עם סימן החיים): גרסה, דגם, מוגן או לא
// /crash  האפליקציה קרסה בטלפון - השגיאה נשמרת ונשלחת אליך כהודעת תקלה
// אין כאן שום מידע אישי: מזהה מכשיר מקוצר (אותו קוד של הבטא), דגם וגרסה
const fs = require('fs');
const path = require('path');
const { log } = require('./util');
const { adminPush } = require('./push');

const FILE = path.join(__dirname, '..', 'devices.json');
let data = { devices: {}, crashes: [] };
try { data = Object.assign(data, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (_) {}
let saveT = null;
function save() {
  clearTimeout(saveT);
  saveT = setTimeout(() => fs.writeFile(FILE + '.tmp', JSON.stringify(data), e => { if (!e) fs.rename(FILE + '.tmp', FILE, () => {}); }), 2000);
}

const clean = (v, n) => String(v || '').replace(/[\u0000-\u001f]/g, ' ').slice(0, n);
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
    const c = { d: code, v: clean(j.v, 16), m: clean(j.m, 40), at: +j.at || Date.now(), got: Date.now(), err: clean(j.err, 12000) };
    data.crashes.unshift(c);
    data.crashes.length = Math.min(data.crashes.length, 50);
    save();
    const first = (c.err.split('\n')[0] || '').slice(0, 120);
    log('crash', code, c.v, first);
    adminPush(`💥 קריסה ב-${c.m || code} (גרסה ${c.v}): ${first}`);
    res.writeHead(204); res.end();
  });
}

/** לאתר: המכשירים (הכי חדש למעלה) והקריסות האחרונות */
function list() {
  return {
    devices: Object.entries(data.devices).map(([d, x]) => Object.assign({ d }, x)).sort((a, b) => b.at - a.at),
    crashes: data.crashes,
  };
}

module.exports = { ping, crash, list };
