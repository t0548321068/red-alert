// כלים משותפים: יומן, בקשת https, זמן ישראל, ומניעת בקשות כפולות לאותו מקור
const https = require('https');

const log = (...a) => console.log(new Date().toISOString(), ...a);

// חיבורים נפרדים מהבדיקה החיה של פיקוד העורף - כדי לא לעכב אותה
const slowAgent = new https.Agent({ keepAlive: true, maxSockets: 6 });
function get(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { agent: slowAgent, timeout: 8000, headers: { 'User-Agent': 'Mozilla/5.0 RedAlertPush', ...headers } }, res => {
      const bufs = [];
      res.on('data', d => bufs.push(d));
      res.on('end', () => {
        const b = Buffer.concat(bufs);
        let t = b.toString('utf8').replace(/^﻿/, '').trim();
        if (t.includes('\u0000')) t = b.toString('utf16le').replace(/^﻿/, '').trim();
        res.statusCode === 200 ? resolve(t) : reject(Object.assign(new Error('HTTP ' + res.statusCode), { status: res.statusCode }));
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}
// בקשה שמחזירה רק את כתובת ההפניה (Location)
function head(url) {
  return new Promise(resolve => {
    const req = https.get(url, { agent: slowAgent, timeout: 8000, headers: { 'User-Agent': 'Mozilla/5.0 RedAlertPush' } }, res => {
      resolve(res.headers.location || ''); res.resume();
    });
    req.on('timeout', () => req.destroy()); req.on('error', () => resolve(''));
  });
}

// "2026-10-24 20:10:05" בשעון ישראל -> זמן אמיתי
const ilFmt = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Jerusalem', hourCycle: 'h23',
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
function ilTime(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(s); if (!m) return 0;
  const asUtc = Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
  const p = Object.fromEntries(ilFmt.formatToParts(new Date(asUtc)).map(x => [x.type, x.value]));
  const off = Date.UTC(+p.year, p.month - 1, +p.day, +p.hour, +p.minute, +p.second) - asUtc;
  return asUtc - off;
}

// בקשה שעוד לא חזרה - לא שולחים עוד אחת לאותו מקור
const busy = new Set();
async function once(key, fn) {
  if (busy.has(key)) return;
  busy.add(key);
  try { await fn(); } finally { busy.delete(key); }
}
// שגיאה ממקור - ביומן לכל היותר פעם בדקה
const errAt = {};
function fail(k, e) { if (Date.now() - (errAt[k] || 0) > 60000) { errAt[k] = Date.now(); log('source error', k, e.message); } }

module.exports = { log, get, head, ilTime, once, fail, slowAgent };
