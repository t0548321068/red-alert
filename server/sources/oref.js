// פיקוד העורף: הקובץ החי (כל שנייה, בחיבור קבוע) + היסטוריה וארכיון (גיבוי להתרעות שהקובץ החי פספס)
const https = require('https');
const { get, ilTime, once, fail, log, OREF_HEADERS } = require('../lib/util');
const P = require('../lib/parse');   // הפענוח עצמו (נבדק בבדיקות)
const { send, state, saveState } = require('../lib/push');
const { ok } = require('../lib/health');
const egress = require('../lib/egress');

const OREF_H = OREF_HEADERS;

// ---- הדרך לפיקוד העורף: ישירות מהשרת, וכשהשרת חסום (403) - דרך Cloudflare ----
// פיקוד העורף חוסם את Oracle מנובמבר 2026. ה-Worker ב-Cloudflare (server/deploy/cloudflare/worker.js) מביא את אותם קבצים.
// אחרי חסימה עובדים דרך Cloudflare חצי שעה, ואז מנסים שוב ישירות
const CF = 'https://oref-proxy.t0548321068.workers.dev/';
let directDownUntil = 0, via = '';
function viaLog(v, why) { if (v !== via) { via = v; log('oref: עובדים', v === 'cf' ? 'דרך Cloudflare' : 'ישירות מהשרת', why || ''); } }
async function orefGet(kind, url) {
  if (Date.now() >= directDownUntil) {
    const ip = egress.current();
    try { const t = await get(url, OREF_H, { localAddress: ip }); viaLog('direct'); egress.works(ip); return t; }
    catch (e) {
      if (e.status !== 403) throw e;
      egress.blocked(ip);
      directDownUntil = Date.now() + 30 * 60 * 1000;
    }
  }
  const key = require('../lib/relay').key();
  const t = await get(CF + kind, { 'x-key': key });
  viaLog('cf', '(השרת חסום)');
  return t;
}

// ---- הקובץ החי ----
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
    let txt;
    try { txt = await orefGet('live', 'https://www.oref.org.il/warningMessages/alert/Alerts.json?t=' + Date.now()); }
    catch (e) { bad(e.message, ''); return; }
    txt = txt.replace(/\u0000/g, '').trim();
    // תקין רק אם ריק או JSON - דף שגיאה/חסימה לא נחשב "עונה"
    if (!txt) { ok('oref', via); return; }
    try { JSON.parse(txt); ok('oref', via); } catch (_) { bad('not JSON', txt); return; }
    liveText(txt);
  });
}
/** תוכן הקובץ החי (מהשרת או מהממסר בבית) - התרעה חדשה נשלחת. מחזיר true אם נשלחה */
function liveText(txt) {
  try {
    const j = P.orefLive(txt);
    if (!j || j.id === String(state.orefId)) return false;
    state.orefId = j.id; saveState();   // נשמר - אחרי הפעלה מחדש לא שולחים שוב את אותה התרעה
    if (j.areas.length) { send(j.title, j.areas, 'oref', Date.now(), j.id); return true; }   // המזהה - מטח חדש לא נבלע ככפילות
  } catch (_) { /* תשובה לא תקינה - בסבב הבא */ }
  return false;
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
      const t = await orefGet(key, url);
      historyText(key, t, via);
    } catch (e) { fail(key, e); }
  }); }
}
/** תשובת היסטוריה / ארכיון (מהשרת או מהממסר) - התרעות מ-90 השניות האחרונות נשלחות. null = תשובה לא תקינה */
function historyText(key, t, from = 'direct') {
  // הארכיון - רק תשובה בפורמט שלו נחשבת "עונה" (לא דף אתר אחרי הפניה)
  const rows = key === 'archive' ? archive.convert(t) : (t.startsWith('[') ? JSON.parse(t) : (t ? null : []));
  if (!rows) return null;
  ok(key, from);
  if (key === 'archive') archive.ingest(rows);   // גם לרשימת ההתרעות האחרונות
  let n = 0;
  for (const e of P.historyGroups(rows)) { send(e.title, e.areas, key, e.at); n++; }
  return n;
}

// בדיקת חסימה: פעם ב-5 דקות דף הבית של פיקוד העורף - ביומן רק כשהתשובה משתנה (חסום / פתוח)
let homeSt = '';
function probeHome() {
  https.get({ host: 'www.oref.org.il', path: '/', timeout: 8000, headers: OREF_H, localAddress: egress.current() }, res => {
    res.resume();
    const st = String(res.statusCode);
    if (st !== homeSt) { log('oref home', st === '200' ? 'פתוח (200)' : 'HTTP ' + st); homeSt = st; }
  }).on('timeout', function () { this.destroy(); }).on('error', e => log('oref home error', e.code));
}

function start() {
  egress.setup();
  setInterval(pollLive, LIVE_MS);
  setInterval(pollHistory, 1000);
  probeHome(); setInterval(probeHome, 5 * 60 * 1000);
}

module.exports = { start, OREF_H, liveText, historyText };
