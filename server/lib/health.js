// מצב המקורות: מתי כל מקור ענה לאחרונה (נשלח בסימן החיים), והתראה לך כשמקור לא עונה 10 דקות
const { adminPush } = require('./push');

const STARTED = Date.now();
const okAt = {};
// מאיפה כל מקור מחובר: direct (ישירות מהשרת) / cf (דרך Cloudflare) / relay (דרך הממסר בבית)
const viaOf = {};
const ok = (k, via = 'direct') => { okAt[k] = Date.now(); viaOf[k] = via; };
/** לסימן החיים: מקורות שלא מחוברים ישירות - "oref=cf,history=cf" */
const viaList = () => Object.keys(viaOf).filter(k => viaOf[k] !== 'direct' && Date.now() - okAt[k] < 5 * 60 * 1000).map(k => k + '=' + viaOf[k]).join(',');
let tzofarOpen = false;
const setTzofar = v => { tzofarOpen = v; };
function upList() {
  const up = Object.keys(okAt).filter(k => Date.now() - okAt[k] < 5 * 60 * 1000);
  if (tzofarOpen && !up.includes('tzofar')) up.push('tzofar');
  return up;
}

const ALL_SOURCES = ['oref', 'history', 'archive', 'tzofar', 'tg:PikudHaOref_all', 'tg:tzevaadomm', 'tg:CumtaAlertsChannel', 'tg:Radar_Alerts'];
const SRC_NAMES = { oref: 'פיקוד העורף – חי', history: 'פיקוד העורף – היסטוריה', archive: 'פיקוד העורף – ארכיון', tzofar: 'צופר',
  'tg:PikudHaOref_all': 'טלגרם – פיקוד העורף', 'tg:tzevaadomm': 'טלגרם – צופר', 'tg:CumtaAlertsChannel': 'טלגרם – כומתה', 'tg:Radar_Alerts': 'טלגרם – רדאר' };
const downSince = {}, alerted = {};
function watchDown() {
  setInterval(() => {
    if (Date.now() - STARTED < 5 * 60 * 1000) return;   // אחרי הפעלה מחדש - זמן להתחבר
    const up = new Set(upList());
    for (const k of ALL_SOURCES) {
      if (up.has(k)) {
        if (alerted[k]) { alerted[k] = false; adminPush(`✅ ${SRC_NAMES[k]} חזר לעבוד`); }
        delete downSince[k];
      } else {
        downSince[k] = downSince[k] || Date.now();
        if (!alerted[k] && Date.now() - downSince[k] > 10 * 60 * 1000) { alerted[k] = true; adminPush(`⚠️ ${SRC_NAMES[k]} לא עונה לשרת כבר 10 דקות`); }
      }
    }
  }, 60 * 1000);
}

// ---- הדיסק בשרת: התראה לך ב-80% (ושוב ב-90%), וכשחוזר מתחת ל-75% ----
const fs = require('fs');
const path = require('path');
let diskPct = 0, diskLevel = 0;   // 0 תקין, 1 מעל 80%, 2 מעל 90%
function readDisk() {
  const dir = path.join(__dirname, '..');
  try {
    if (fs.statfsSync) { const s = fs.statfsSync(dir); return Math.round((1 - s.bavail / s.blocks) * 100); }
  } catch (_) { }
  try {
    const out = require('child_process').execFileSync('df', ['-Pk', dir], { encoding: 'utf8' }).trim().split('\n').pop().split(/\s+/);
    return parseInt(out[4], 10);
  } catch (_) { return 0; }
}
function checkDisk() {
  diskPct = readDisk();
  if (!diskPct) return;
  const lvl = diskPct >= 90 ? 2 : diskPct >= 80 ? 1 : diskPct < 75 ? 0 : diskLevel;
  if (lvl > diskLevel) adminPush(lvl === 2 ? `🛑 הדיסק בשרת כמעט מלא (${diskPct}%) – שמירת הרחובות הופסקה` : `⚠️ הדיסק בשרת מתמלא (${diskPct}%)`);
  else if (lvl === 0 && diskLevel > 0) adminPush(`✅ הדיסק בשרת חזר לתקין (${diskPct}%)`);
  diskLevel = lvl;
}
function watchDisk() { setTimeout(checkDisk, 2 * 60 * 1000); setInterval(checkDisk, 30 * 60 * 1000); }
/** מעל 90% - לא שומרים עוד רחובות בדיסק (רק מעבירים), כדי לא לפגוע בהתרעות */
const diskCritical = () => diskLevel >= 2;

module.exports = { STARTED, ok, setTzofar, upList, watchDown, viaList, watchDisk, diskCritical, diskPct: () => diskPct };
