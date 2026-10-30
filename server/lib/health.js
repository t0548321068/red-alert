// מצב המקורות: מתי כל מקור ענה לאחרונה (נשלח בסימן החיים), והתראה לך כשמקור לא עונה 10 דקות
const { adminPush } = require('./push');

const STARTED = Date.now();
const okAt = {};
const ok = k => { okAt[k] = Date.now(); };
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

module.exports = { STARTED, ok, setTzofar, upList, watchDown };
