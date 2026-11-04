// כלים משותפים: יומן, בקשת https, זמן ישראל, ומניעת בקשות כפולות לאותו מקור
const https = require('https');

// יומן: למסוף (journald) + 500 השורות האחרונות בזיכרון - לאתר
const logLines = [];
const log = (...a) => {
  const t = new Date().toISOString();
  console.log(t, ...a);
  logLines.push(t + ' ' + a.map(x => typeof x === 'string' ? x : x instanceof Error ? x.message : JSON.stringify(x)).join(' ').slice(0, 1000));
  if (logLines.length > 500) logLines.shift();
};

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
        // שגיאה: גם כותרת הדף שהתקבל (למשל דף החסימה) - ביומן, כדי לדעת מי חוסם
        const title = (/<title[^>]*>([^<]*)<\/title>/i.exec(t) || [])[1];
        res.statusCode === 200 ? resolve(t) : reject(Object.assign(new Error('HTTP ' + res.statusCode + (title ? ' "' + title.trim().slice(0, 80) + '"' : '')), { status: res.statusCode }));
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}
// פיקוד העורף: מזדהים כמו דפדפן כרום רגיל (מנובמבר 2026 האתר חוסם בקשות שלא נראות כמו דפדפן)
const OREF_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
  Accept: 'application/json, text/plain, */*',
  'Accept-Language': 'he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7',
  Referer: 'https://www.oref.org.il/',
  'X-Requested-With': 'XMLHttpRequest',
  'Sec-Fetch-Site': 'same-origin', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Dest': 'empty',
  'sec-ch-ua': '"Google Chrome";v="141", "Not?A_Brand";v="8", "Chromium";v="141"', 'sec-ch-ua-mobile': '?0', 'sec-ch-ua-platform': '"Windows"',
};
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
  // ההפרש נבדק פעמיים: בפעם השנייה ברגע האמיתי (ליד מעבר שעון הפעם הראשונה טועה בשעה)
  const off1 = ilOffset(asUtc);
  return asUtc - ilOffset(asUtc - off1);
}
function ilOffset(t) {
  const p = Object.fromEntries(ilFmt.formatToParts(new Date(t)).map(x => [x.type, x.value]));
  return Date.UTC(+p.year, p.month - 1, +p.day, +p.hour, +p.minute, +p.second) - t;
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

module.exports = { log, logLines, get, head, ilTime, once, fail, slowAgent, OREF_HEADERS };
