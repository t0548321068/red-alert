// שרת ההתראות: בודק את כל המקורות - פיקוד העורף (חי, היסטוריה, ארכיון), צופר ו-4 ערוצי טלגרם -
// ושולח כל התרעה חדשה מיד לכל הטלפונים דרך Firebase, ופעם ב-10 דקות סימן חיים עם מצב המקורות.
// כשסימן החיים לא מגיע - הטלפון חוזר לבדוק בעצמו (גיבוי).
//
// lib/push.js      שליחה לטלפונים (התרעות, סימן חיים, תקלות) ומניעת כפילויות
// lib/health.js    מצב המקורות, והתראה כשמקור לא עונה
// sources/         פיקוד העורף, צופר, טלגרם
// lib/archive.js   התרעות אחרונות (שנה אחורה)
// lib/weather.js   מזג אוויר ושם המקום
// lib/shabbat.js   זמני שבת וחג
// lib/files.js     קבצי נתונים מתעדכנים ומכשירי בטא
// lib/versions.js  הודעה על גרסה חדשה
const { log } = require('./lib/util');
const push = require('./lib/push');
const health = require('./lib/health');
const archive = require('./lib/archive');
const weather = require('./lib/weather');
const shabbat = require('./lib/shabbat');
const files = require('./lib/files');
const versions = require('./lib/versions');

// תקלה לא צפויה - רושמים ביומן וממשיכים (שהבדיקה וההתרעות לא ייעצרו)
process.on('uncaughtException', e => log('uncaught', e && e.stack || e));
process.on('unhandledRejection', e => log('unhandled', e && e.stack || e));

// ---- סימן חיים ----
const heartbeat = () => push.heartbeat(health.upList(), files.dataIndex.v);
setInterval(heartbeat, 10 * 60 * 1000);
setTimeout(heartbeat, 20 * 1000);

// ---- הגנה: עד 60 בקשות בדקה מכל כתובת; מזג אוויר וזמני שבת רק למיקומים בישראל ----
const ipHits = new Map();
function tooMany(ip) {
  const now = Date.now(), h = ipHits.get(ip);
  if (!h || now - h.start > 60 * 1000) { ipHits.set(ip, { start: now, n: 1 }); return false; }
  return ++h.n > 60;
}
setInterval(() => { const now = Date.now(); for (const [ip, h] of ipHits) if (now - h.start > 60 * 1000) ipHits.delete(ip); }, 60 * 1000);
const inIsrael = (lat, lon) => lat > 29.3 && lat < 33.5 && lon > 34.2 && lon < 35.95;

// ---- בקשות מהאפליקציה ----
let lastManualHb = 0;
function route(req, res) {
  const u = new URL(req.url, 'http://x');
  const q = k => u.searchParams.get(k);
  if (tooMany(req.socket.remoteAddress || '')) { res.writeHead(429); return res.end(); }
  if ((u.pathname === '/weather' || u.pathname === '/shabbat') && !inIsrael(+q('lat'), +q('lon'))) { res.writeHead(400); return res.end(); }
  switch (u.pathname) {
    case '/archive': return archive.serve(req, res, +(q('since') || 0));
    case '/weather': return weather.serve(res, +q('lat'), +q('lon'));
    case '/shabbat': return shabbat.serve(res, +q('lat'), +q('lon'));
    case '/beta': return files.serveBeta(res, String(q('code') || '').trim().toUpperCase());
    case '/status':
      // כפתור "בדוק את השרתים": מצב המקורות, וסימן חיים דרך Firebase - כך נבדקת כל הדרך עד הטלפון.
      // לכל היותר פעם בדקה (שלא יוכלו להציף את המכשירים מבחוץ)
      if (Date.now() - lastManualHb > 60 * 1000) { lastManualHb = Date.now(); heartbeat(); }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ up: health.upList(), started: health.STARTED, now: Date.now(), version: versions.latest }));
  }
  if (u.pathname === '/data' || u.pathname.startsWith('/data/')) return files.serveData(res, decodeURIComponent(u.pathname.slice(6)));
  res.writeHead(404); res.end();
}
require('http').createServer((req, res) => {
  // בקשה פגומה (כתובת לא תקינה וכד') - עונים 400, לא מפילים את השרת
  try { route(req, res); } catch (e) { log('bad request', e.message); try { res.writeHead(400); res.end(); } catch (_) {} }
}).listen(8080);

// ---- הפעלה ----
require('./sources/oref').start();
require('./sources/tzofar').start();
require('./sources/telegram').start();
archive.start();
versions.start();
health.watchDown();
log('red-alert push server started');
