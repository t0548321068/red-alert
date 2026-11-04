// כתובות היציאה לפיקוד העורף: לשרת כמה כתובות IP (הראשית + 3 נוספות ב-Oracle).
// פיקוד העורף חוסם כתובת שמבקשת יותר מדי - כשכתובת מקבלת "הגישה נדחתה" עוברים לבאה, וחוזרים לנסות אותה אחרי חצי שעה.
const { execFile } = require('child_process');
const { log } = require('./util');

// כתובת פרטית -> הכתובת הציבורית שלה (לתצוגה ביומן)
const IPS = [
  ['10.0.0.150', '151.145.91.93'],    // הראשית
  ['10.0.0.180', '151.145.80.116'],
  ['10.0.0.204', '151.145.84.117'],
  ['10.0.0.208', '129.159.133.6'],
];
const BLOCK_MS = 30 * 60 * 1000;
const blockedAt = {};
let ready = new Set(['10.0.0.150']);   // הכתובות שמוגדרות כבר בכרטיס הרשת
let cur = 0;

// Oracle לא מוסיף את הכתובות הנוספות למערכת לבד - מוסיפים אותן לכרטיס הרשת (גם אחרי הפעלה מחדש של השרת)
function setup() {
  execFile('ip', ['-o', '-4', 'route', 'show', 'default'], (e, out) => {
    const dev = (/ dev (\S+)/.exec(out || '') || [])[1];
    if (!dev) return log('egress: לא נמצא כרטיס רשת');
    execFile('ip', ['-o', '-4', 'addr', 'show', 'dev', dev], (e2, have) => {
      for (const [ip] of IPS) {
        if ((have || '').includes(' ' + ip + '/')) { ready.add(ip); continue; }
        execFile('sudo', ['-n', 'ip', 'addr', 'add', ip + '/24', 'dev', dev], err => {
          if (err) log('egress: לא הצליח להוסיף', ip, String(err.message).slice(0, 80));
          else { ready.add(ip); log('egress: נוספה כתובת', ip); }
        });
      }
    });
  });
}

/** הכתובת הנוכחית ליציאה (פרטית - ל-localAddress) */
function current() {
  for (let i = 0; i < IPS.length; i++) {
    const [ip] = IPS[(cur + i) % IPS.length];
    if (ready.has(ip) && !(Date.now() - (blockedAt[ip] || 0) < BLOCK_MS)) { cur = (cur + i) % IPS.length; return ip; }
  }
  return IPS[cur][0];   // כולן חסומות - ממשיכים עם הנוכחית
}

/** הכתובת קיבלה "הגישה נדחתה" - עוברים לבאה */
function blocked(ip) {
  if (Date.now() - (blockedAt[ip] || 0) < BLOCK_MS) return;
  blockedAt[ip] = Date.now();
  const pub = (IPS.find(x => x[0] === ip) || [])[1] || ip;
  cur = (IPS.findIndex(x => x[0] === ip) + 1) % IPS.length;
  log('egress: כתובת חסומה', pub, '- עוברים ל', (IPS.find(x => x[0] === current()) || [])[1]);
}

/** הכתובת עובדת - ביומן כשעוברים אליה */
let okIp = '';
function works(ip) { if (ip !== okIp) { okIp = ip; log('egress: פיקוד העורף עונה דרך', (IPS.find(x => x[0] === ip) || [])[1] || ip); } }

module.exports = { setup, current, blocked, works };
