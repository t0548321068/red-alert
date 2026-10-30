// האתר (tzofar-app.duckdns.org): רק לך - כניסה עם קוד גישה.
// הקוד עצמו לא נשמר בשום מקום - רק "טביעה" שלו (scrypt) בקובץ web_auth.json בשרת (לא נכנס לגיט).
// מגדירים / מחליפים קוד: node tools/set-web-code.js בשרת
//
// /            האתר (אחרי כניסה) או מסך הכניסה
// /api/login   בדיקת הקוד -> עוגייה חתומה לשנה
// /api/live    התרעות בזמן אמת (Server-Sent Events) + ההתרעות של חצי השעה האחרונה בהתחברות
// /api/state   מצב השרת והמקורות
// /api/devices המכשירים והקריסות
// /api/message שליחת הודעה לטלפונים (לכולם או למכשיר אחד)
// /api/server  יומן, גרסה, עדכון עצמי;  /api/restart הפעלה מחדש
// /app/        ה-PWA - נפרד לגמרי מהאתר: מסך זהה לאפליקציה (web/m), manifest ו-sw משלו, רק הוא ניתן להתקנה.
//              האתר (/) לא ניתן להתקנה ולא מוביל ל-PWA
// /assets/...  קבצי המפה והיישובים מתוך האפליקציה (אותם קבצים בדיוק)
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { log } = require('./util');
const devices = require('./devices');
const admin = require('./admin');

const AUTH_FILE = path.join(__dirname, '..', 'web_auth.json');
const WEB_DIR = path.join(__dirname, '..', 'web');
const APP_ASSETS = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets');
const APP_RAW = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'res', 'raw');

function auth() { try { return JSON.parse(fs.readFileSync(AUTH_FILE, 'utf8')); } catch (_) { return null; } }

// ---- עוגיית כניסה: "זמן.חתימה" - חתומה בסוד שבקובץ, כך שאי אפשר לזייף ----
function sign(secret, v) { return crypto.createHmac('sha256', secret).update(v).digest('base64url'); }
function makeToken(a) { const t = String(Date.now()); return t + '.' + sign(a.secret, t); }
function loggedIn(req) {
  const a = auth(); if (!a) return false;
  const m = /(?:^|;\s*)tz=([^;]+)/.exec(req.headers.cookie || ''); if (!m) return false;
  const [t, s] = m[1].split('.');
  if (!t || !s) return false;
  const good = sign(a.secret, t);
  if (s.length !== good.length || !crypto.timingSafeEqual(Buffer.from(s), Buffer.from(good))) return false;
  return Date.now() - +t < 365 * 24 * 3600 * 1000;
}

// ---- ניסיונות כניסה: עד 5 בדקה מכל כתובת, ועד 20 בדקה בסך הכול (שלא ינחשו את הקוד) ----
const tries = new Map(); let allTries = { start: 0, n: 0 };
function tooManyTries(ip) {
  const now = Date.now();
  if (now - allTries.start > 60000) allTries = { start: now, n: 0 };
  const t = tries.get(ip);
  if (!t || now - t.start > 60000) tries.set(ip, { start: now, n: 1 }); else t.n++;
  return ++allTries.n > 20 || tries.get(ip).n > 5;
}
function login(req, res) {
  let body = '';
  req.on('data', d => { body += d; if (body.length > 2000) req.destroy(); });
  req.on('end', () => {
    const a = auth();
    if (!a) { res.writeHead(503); return res.end('{"error":"no code set"}'); }
    if (tooManyTries(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '')) { res.writeHead(429); return res.end('{"error":"wait"}'); }
    let code = '';
    try { code = String(JSON.parse(body).code || ''); } catch (_) {}
    const hash = crypto.scryptSync(code, Buffer.from(a.salt, 'hex'), 32);
    if (!crypto.timingSafeEqual(hash, Buffer.from(a.hash, 'hex'))) { log('web login failed'); res.writeHead(401); return res.end('{"ok":false}'); }
    res.writeHead(200, { 'Content-Type': 'application/json',
      'Set-Cookie': `tz=${makeToken(a)}; Path=/; Max-Age=31536000; HttpOnly; Secure; SameSite=Strict` });
    res.end('{"ok":true}');
  });
}

// ---- התרעות בזמן אמת ----
const clients = new Set();
const recent = [];   // ההתרעות של חצי השעה האחרונה - נשלחות מיד למי שמתחבר
function broadcast(alert) {
  const now = Date.now();
  recent.push(alert);
  while (recent.length && now - recent[0].sent > 30 * 60 * 1000) recent.shift();
  const msg = 'event: alert\ndata: ' + JSON.stringify(alert) + '\n\n';
  for (const r of clients) { try { r.write(msg); } catch (_) {} }
}
function live(req, res) {
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
  res.write('retry: 3000\n\n');
  for (const a of recent) res.write('event: alert\ndata: ' + JSON.stringify(a) + '\n\n');
  res.write('event: ready\ndata: 1\n\n');
  clients.add(res);
  const ping = setInterval(() => { try { res.write(': ping\n\n'); } catch (_) {} }, 20000);
  req.on('close', () => { clearInterval(ping); clients.delete(res); });
}

// ---- קבצים ----
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.txt': 'text/plain; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml',
  '.ogg': 'audio/ogg', '.webmanifest': 'application/manifest+json', '.ico': 'image/x-icon' };
// מתוך האפליקציה - רק הקבצים האלה
const APP_FILES = /^(areas\.js|districts\.json|migun\.txt|mapstyle\.js|leaflet\/[\w.-]+(\/[\w.-]+)?|maplibre\/[\w.-]+)$/;
function sendFile(res, file, cache) {
  fs.stat(file, (e, st) => {
    if (e || !st.isFile()) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': cache ? 'public, max-age=86400' : 'no-cache' });
    fs.createReadStream(file).pipe(res);
  });
}

/** מחזיר true אם הבקשה שייכת לאתר וטופלה */
function handle(req, res, u, state) {
  const p = u.pathname;
  if (p === '/api/login' && req.method === 'POST') { login(req, res); return true; }
  if (/^\/(login\.html|web\.css|icon\.png|icon-512\.png|favicon\.ico)$/.test(p)) {
    sendFile(res, path.join(WEB_DIR, p === '/favicon.ico' ? 'icon.png' : p.slice(1)), true); return true;
  }
  // ה-PWA: קבצי ההתקנה פתוחים (הדפדפן טוען אותם גם לפני כניסה)
  if (/^\/app\/(manifest\.webmanifest|sw\.js|icon\.png|icon-512\.png)$/.test(p)) {
    sendFile(res, path.join(WEB_DIR, 'm', p.slice(5)), !p.endsWith('.js')); return true;
  }
  if (p === '/app') { res.writeHead(301, { Location: '/app/' }); res.end(); return true; }
  const isWeb = p === '/' || p.startsWith('/app/') || p.startsWith('/api/') || p.startsWith('/assets/') || p.startsWith('/w/');
  if (!isWeb) return false;
  if (!loggedIn(req)) {
    if (p === '/' || p === '/app/') { sendFile(res, path.join(WEB_DIR, 'login.html'), false); return true; }
    res.writeHead(401); res.end(); return true;
  }
  if (p === '/') { sendFile(res, path.join(WEB_DIR, 'index.html'), false); return true; }
  // ה-PWA: אותו מסך כמו באפליקציה (web/m)
  if (p === '/app/') { sendFile(res, path.join(WEB_DIR, 'm', 'index.html'), false); return true; }
  if (p.startsWith('/app/')) { const f = p.slice(5); if (/^(ic\/)?[\w.-]+$/.test(f)) sendFile(res, path.join(WEB_DIR, 'm', f), false); else { res.writeHead(404); res.end(); } return true; }
  if (p === '/api/live') { live(req, res); return true; }
  if (p === '/api/state') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(state())); return true; }
  if (p === '/api/server') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(admin.info(state().started))); return true; }
  if (p === '/api/restart' && req.method === 'POST') { admin.restart(res); return true; }
  if (p === '/api/message' && req.method === 'POST') { devices.sendMessage(req, res); return true; }
  if (p === '/api/devices') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(devices.list())); return true; }
  if (p === '/api/logout') {
    res.writeHead(302, { Location: u.searchParams.get('to') === 'app' ? '/app/' : '/', 'Set-Cookie': 'tz=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict' }); res.end(); return true;
  }
  if (p.startsWith('/w/')) { const f = p.slice(3); if (/^[\w.-]+$/.test(f)) sendFile(res, path.join(WEB_DIR, f), false); else { res.writeHead(404); res.end(); } return true; }
  if (p.startsWith('/assets/')) {
    const f = p.slice(8);
    if (f === 'siren.ogg') sendFile(res, path.join(APP_RAW, 'app_siren.ogg'), true);
    else if (APP_FILES.test(f)) sendFile(res, path.join(APP_ASSETS, f), true);
    else { res.writeHead(404); res.end(); }
    return true;
  }
  res.writeHead(404); res.end(); return true;
}

module.exports = { handle, broadcast };
