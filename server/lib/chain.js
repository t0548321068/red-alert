// שרשרת הגיבוי: שרת ראשי (זה) ← שרת משני (Cloudflare) ← ממסר בבית ← הטלפונים בודקים בעצמם.
// כאן: מצב השרת המשני והממסר (לאתר ולסימן החיים), ו"בדיקת גיבוי" - השרת מעמיד פנים שהוא לא עונה כמה דקות
const https = require('https');
const { log } = require('./util');

const SECONDARY = 'https://oref-proxy.t0548321068.workers.dev/health';
const sec = { state: '', at: 0 };   // standby / active / down
let drillUntil = 0, drillKind = '';   // secondary - השרת המשני נכנס לפעולה; relay - הממסר נכנס לפעולה

function pollSecondary() {
  const req = https.get(SECONDARY, { timeout: 8000 }, res => {
    let b = ''; res.on('data', d => { b += d; });
    res.on('end', () => {
      try { const j = JSON.parse(b); sec.state = j.active ? 'active' : 'standby'; } catch (_) { sec.state = 'down'; }
      sec.at = Date.now();
    });
  });
  req.on('timeout', () => req.destroy());
  req.on('error', () => { sec.state = 'down'; sec.at = Date.now(); });
}
setInterval(pollSecondary, 60 * 1000); setTimeout(pollSecondary, 5000);

/** מצב הממסר: מחובר (בהמתנה) אם שלח סימן ב-3 הדקות האחרונות */
function relayState() {
  const s = require('./relay').status();
  const last = Math.max(s.seen.live, s.seen.ping);
  return last && Date.now() - last < 3 * 60 * 1000 ? 'standby' : 'off';
}

function state() { return { primary: drilling() ? 'drill' : 'active', secondary: sec.state || 'unknown', relay: relayState(), drillUntil, drillKind }; }

/**
 * בדיקת גיבוי:
 * secondary - 4 דקות שבהן השרת עונה לשרת המשני ולממסר "לא זמין" - המשני אמור להיכנס לפעולה
 * relay - 6 דקות שבהן השרת עונה "לא זמין" רק לממסר (המשני רואה שהשרת עובד ונשאר בהמתנה) - הממסר אמור להיכנס לפעולה
 * מי שנכנס לפעולה בבדיקה שולח גם התרעת בדיקה לטלפוני הבטא
 */
function drill(res, kind) {
  drillKind = kind === 'relay' ? 'relay' : 'secondary';
  drillUntil = Date.now() + (drillKind === 'relay' ? 6 : 4) * 60 * 1000;
  log('chain: בדיקת גיבוי (' + (drillKind === 'relay' ? 'ממסר' : 'שרת משני') + ') עד', new Date(drillUntil).toISOString());
  res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ until: drillUntil, kind: drillKind }));
}
const drilling = () => Date.now() < drillUntil;
/** האם לענות "לא זמין" לבודק הזה: alive - השרת המשני, ping - הממסר */
const blocks = who => drilling() && (who === 'ping' || drillKind === 'secondary');

module.exports = { state, drill, drilling, blocks };
