// פיקוד העורף: הקובץ החי (כל שנייה, בחיבור קבוע) + היסטוריה וארכיון (גיבוי להתרעות שהקובץ החי פספס)
const https = require('https');
const { get, ilTime, once, fail } = require('../lib/util');
const { send, state, saveState } = require('../lib/push');
const { ok } = require('../lib/health');

const OREF_H = { Referer: 'https://www.oref.org.il/', 'X-Requested-With': 'XMLHttpRequest' };

// ---- הקובץ החי ----
const agent = new https.Agent({ keepAlive: true, maxSockets: 2 });
function fetchLive() {
  return new Promise(resolve => {
    const req = https.get({
      host: 'www.oref.org.il', path: '/WarningMessages/alert/alerts.json?t=' + Date.now(), agent, timeout: 4000,
      headers: { ...OREF_H, Referer: 'https://www.oref.org.il/', 'User-Agent': 'Mozilla/5.0 RedAlertPush' },
    }, res => {
      const bufs = [];
      res.on('data', d => bufs.push(d));
      res.on('end', () => {
        let txt = Buffer.concat(bufs).toString('utf8').replace(/^﻿/, '').trim();
        if (!txt && bufs.length) txt = Buffer.concat(bufs).toString('utf16le').replace(/^﻿/, '').trim();
        ok('oref');
        resolve(txt);
      });
    });
    req.on('timeout', () => req.destroy());
    req.on('error', () => resolve(''));
    req.on('close', () => resolve(''));
  });
}
async function pollLive() {
  // כשהאתר איטי - לא שולחים בקשה חדשה כל שנייה עד שהקודמת חוזרת (שלא יצטבר תור)
  await once('oref', async () => {
    const txt = await fetchLive();
    if (!txt) return;
    try {
      const j = JSON.parse(txt);
      if (!j.id || j.id === state.orefId) return;
      state.orefId = j.id; saveState();   // נשמר - אחרי הפעלה מחדש לא שולחים שוב את אותה התרעה
      if (Array.isArray(j.data) && j.data.length) send(j.title || 'התראה', j.data, 'oref');
    } catch (_) { /* תשובה לא תקינה - בסבב הבא */ }
  });
}

// ---- היסטוריה וארכיון ----
const HISTORY = [
  ['history', 'https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json'],
  ['archive', 'https://alerts-history.oref.org.il/Shared/Ajax/GetAlarmsHistory.aspx?lang=he&mode=1'],
];
function pollHistory() {
  for (const [key, url] of HISTORY) once(key, async () => {
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
    } catch (e) { fail(key, e); }
  });
}

function start() {
  setInterval(pollLive, 1000);
  setInterval(pollHistory, 1000);
}

module.exports = { start, OREF_H };
