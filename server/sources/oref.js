// פיקוד העורף: הקובץ החי (כל שנייה, בחיבור קבוע) + היסטוריה וארכיון (גיבוי להתרעות שהקובץ החי פספס)
const https = require('https');
const { get, ilTime, once, fail, log, OREF_HEADERS } = require('../lib/util');
const { send, state, saveState } = require('../lib/push');
const { ok } = require('../lib/health');

const OREF_H = OREF_HEADERS;

// ---- הקובץ החי ----
const agent = new https.Agent({ keepAlive: true, maxSockets: 2 });
function fetchLive() {
  return new Promise(resolve => {
    const req = https.get({
      host: 'www.oref.org.il', path: '/warningMessages/alert/Alerts.json?t=' + Date.now(), agent, timeout: 4000,
      headers: OREF_H,
    }, res => {
      const bufs = [];
      res.on('data', d => bufs.push(d));
      res.on('end', () => {
        const buf = Buffer.concat(bufs);
        // UTF-16 (עם BOM של FF FE) או UTF-8; בלי תווי אפס ו-BOM
        let txt = (buf[0] === 0xFF && buf[1] === 0xFE ? buf.toString('utf16le') : buf.toString('utf8')).replace(/^\uFEFF/, '').replace(/\u0000/g, '').trim();
        if (res.statusCode !== 200) { bad('HTTP ' + res.statusCode, (/<title[^>]*>([^<]*)<\/title>/i.exec(txt) || [])[1] || txt); return resolve(''); }
        // תקין רק אם ריק או JSON - דף שגיאה/חסימה עם 200 לא נחשב "עונה"
        if (!txt) ok('oref');
        else { try { JSON.parse(txt); ok('oref'); } catch (_) { bad('not JSON', txt); } }
        resolve(txt);
      });
    });
    req.on('timeout', () => { bad('timeout', ''); req.destroy(); });
    req.on('error', e => { bad('error ' + e.code, e.message); resolve(''); });
    req.on('close', () => resolve(''));
  });
}
// תשובה לא תקינה - ביומן (לכל היותר פעם בדקה), כדי לדעת למה "לא עונה"
let badAt = 0;
function bad(why, txt) {
  if (Date.now() - badAt < 60 * 1000) return;
  badAt = Date.now();
  log('oref live bad:', why, JSON.stringify(String(txt).slice(0, 120)));
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
      if (Array.isArray(j.data) && j.data.length) send(j.title || 'התראה', j.data, 'oref', Date.now(), String(j.id));   // המזהה - מטח חדש לא נבלע ככפילות
    } catch (_) { /* תשובה לא תקינה - בסבב הבא */ }
  });
}

// ---- היסטוריה וארכיון ----
const archive = require('../lib/archive');
// קצב הבדיקות (נובמבר 2026): 3 בקשות בשנייה גרמו כנראה לחסימת השרת - עכשיו כמו האתר הרשמי ופחות
const LIVE_MS = 3000;
const HISTORY = [
  ['history', 'https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json', 10 * 1000],
  ['archive', archive.ARCH_BASE + 'lastDay', 60 * 1000],   // הארכיון החדש (נובמבר 2026)
];
const lastPoll = {};
function pollHistory() {
  for (const [key, url, every] of HISTORY) if (Date.now() - (lastPoll[key] || 0) >= every) { lastPoll[key] = Date.now(); once(key, async () => {
    try {
      const t = await get(url, OREF_H);
      // הארכיון - רק תשובה בפורמט שלו נחשבת "עונה" (לא דף אתר אחרי הפניה)
      const rows = key === 'archive' ? archive.convert(t) : (t.startsWith('[') ? JSON.parse(t) : (t ? null : []));
      if (!rows) return;
      ok(key);
      const by = new Map();
      for (const o of rows) {
        const at = ilTime(String(o.alertDate || ''));
        if (!at || Date.now() - at > 90 * 1000) continue;
        const title = o.title || o.category_desc || 'התראה';
        const k = title + '|' + at;
        if (!by.has(k)) by.set(k, { title, at, areas: [] });
        by.get(k).areas.push(o.data);
      }
      for (const e of by.values()) send(e.title, e.areas, key, e.at);
    } catch (e) { fail(key, e); }
  }); }
}

// בדיקת חסימה: פעם ב-5 דקות דף הבית של פיקוד העורף - ביומן רק כשהתשובה משתנה (חסום / פתוח)
let homeSt = '';
function probeHome() {
  https.get({ host: 'www.oref.org.il', path: '/', timeout: 8000, headers: OREF_H }, res => {
    res.resume();
    const st = String(res.statusCode);
    if (st !== homeSt) { log('oref home', st === '200' ? 'פתוח (200)' : 'HTTP ' + st); homeSt = st; }
  }).on('timeout', function () { this.destroy(); }).on('error', e => log('oref home error', e.code));
}

function start() {
  setInterval(pollLive, LIVE_MS);
  setInterval(pollHistory, 1000);
  probeHome(); setInterval(probeHome, 5 * 60 * 1000);
}

module.exports = { start, OREF_H };
