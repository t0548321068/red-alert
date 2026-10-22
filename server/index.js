// שרת ההתראות: בודק את פיקוד העורף כל שנייה ומאזין לצופר, וכל התרעה חדשה נשלחת מיד
// לכל הטלפונים דרך Firebase (נושא "alerts"). הטלפון לא צריך להאזין בעצמו - חוסך סוללה.
// מפתח השרת של Firebase: הקובץ service-account.json באותה תיקייה (לא נכנס לגיט).
const fs = require('fs');
const path = require('path');
const https = require('https');
const WebSocket = require('ws');
const admin = require('firebase-admin');

admin.initializeApp({ credential: admin.credential.cert(require(path.join(__dirname, 'service-account.json'))) });
const TOPIC = 'alerts';
const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---- שליחה ----
// הודעת Firebase מוגבלת ל-4KB - התרעה עם הרבה אזורים (כל הארץ) נשלחת בכמה חלקים
async function send(title, areas, src) {
  const chunks = [];
  let cur = [];
  for (const a of areas) {
    if (Buffer.byteLength(JSON.stringify([...cur, a])) > 3000) { chunks.push(cur); cur = []; }
    cur.push(a);
  }
  if (cur.length) chunks.push(cur);
  const at = String(Date.now());
  for (const part of chunks) {
    try {
      await admin.messaging().send({
        topic: TOPIC,
        data: { type: 'alert', title, areas: part.join('|'), src, at },
        android: { priority: 'high', ttl: 60 * 1000 },
      });
    } catch (e) { log('send failed', e.message); }
  }
  log('sent', src, title, areas.length + ' areas', chunks.length + ' msg');
}

// ---- פיקוד העורף: הקובץ החי, כל שנייה, בחיבור קבוע ----
const agent = new https.Agent({ keepAlive: true, maxSockets: 2 });
let lastOrefId = '';
function pollOref() {
  const req = https.get({
    host: 'www.oref.org.il', path: '/WarningMessages/alert/alerts.json?t=' + Date.now(), agent, timeout: 4000,
    headers: { Referer: 'https://www.oref.org.il/', 'X-Requested-With': 'XMLHttpRequest', 'User-Agent': 'Mozilla/5.0 RedAlertPush' },
  }, res => {
    const bufs = [];
    res.on('data', d => bufs.push(d));
    res.on('end', () => {
      let txt = Buffer.concat(bufs).toString('utf8').replace(/^﻿/, '').trim();
      if (!txt && bufs.length) txt = Buffer.concat(bufs).toString('utf16le').replace(/^﻿/, '').trim();
      if (!txt) return;
      try {
        const j = JSON.parse(txt);
        if (!j.id || j.id === lastOrefId) return;
        lastOrefId = j.id;
        if (Array.isArray(j.data) && j.data.length) send(j.title || 'התראה', j.data, 'oref');
      } catch (e) { /* תשובה לא תקינה - בסבב הבא */ }
    });
  });
  req.on('timeout', () => req.destroy());
  req.on('error', () => {});
}
setInterval(pollOref, 1000);

// ---- צופר: חיבור push ----
const THREATS = { 0: 'ירי רקטות וטילים', 1: 'אירוע חומרים מסוכנים', 2: 'חדירת מחבלים', 3: 'רעידת אדמה', 4: 'חשש לצונאמי',
  5: 'חדירת כלי טיס עוין', 6: 'חשש לאירוע רדיולוגי', 7: 'חשש לאירוע כימי', 8: 'התרעות פיקוד העורף' };
const PRE = 'בדקות הקרובות צפויות להתקבל התרעות באזורך', END = 'האירוע הסתיים';
const cityIds = {};
for (const l of fs.readFileSync(path.join(__dirname, 'tzofar_ids.txt'), 'utf8').split('\n')) {
  const [id, name] = l.split('\t'); if (id && name) cityIds[+id] = name.trim();
}
const seen = new Set();
function tzofar() {
  const ws = new WebSocket('wss://ws.tzevaadom.co.il/socket?platform=ANDROID',
    { headers: { Origin: 'https://www.tzevaadom.co.il', 'User-Agent': 'Mozilla/5.0 RedAlertPush' } });
  let alive = setInterval(() => { try { ws.ping(); } catch (_) {} }, 30000);
  ws.on('open', () => log('tzofar connected'));
  ws.on('message', buf => {
    try {
      const m = JSON.parse(buf.toString()); const d = m.data; if (!d) return;
      const id = m.type + '_' + d.notificationId; if (seen.has(id)) return; seen.add(id);
      if (seen.size > 500) seen.delete(seen.values().next().value);
      if (m.type === 'ALERT' && !d.isDrill && THREATS[d.threat ?? 0] && Array.isArray(d.cities)) {
        send(THREATS[d.threat ?? 0], d.cities, 'tzofar');
      } else if (m.type === 'SYSTEM_MESSAGE' && (d.instructionType === 0 || d.instructionType === 1)) {
        const areas = (d.citiesIds || []).map(i => cityIds[i]).filter(Boolean);
        if (areas.length) send(d.instructionType === 0 ? PRE : END, areas, 'tzofar');
      }
    } catch (_) {}
  });
  const again = () => { clearInterval(alive); setTimeout(tzofar, 5000); };
  ws.on('close', again);
  ws.on('error', () => { try { ws.terminate(); } catch (_) {} });
}
tzofar();
log('red-alert push server started');
