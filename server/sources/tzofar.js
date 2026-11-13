// צופר: חיבור push (websocket) - ההתרעה מגיעה מיד
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const { log } = require('../lib/util');
const { send } = require('../lib/push');
const { setTzofar } = require('../lib/health');

const P = require('../lib/parse');   // הפענוח עצמו (נבדק בבדיקות)
const { PRE, END } = P;

// מזהה עיר של צופר -> שם אזור של פיקוד העורף
const cityIds = {};
for (const l of fs.readFileSync(path.join(__dirname, '..', 'tzofar_ids.txt'), 'utf8').split('\n')) {
  const [id, name] = l.split('\t'); if (id && name) cityIds[+id] = name.trim();
}

const seen = new Set();
function connect() {
  const ws = new WebSocket('wss://ws.tzevaadom.co.il/socket?platform=ANDROID',
    { headers: { Origin: 'https://www.tzevaadom.co.il', 'User-Agent': 'Mozilla/5.0 RedAlertPush' } });
  // חיבור "חצי פתוח" (בלי pong מאז הבדיקה הקודמת) - סוגרים, ו-close מתחבר מחדש
  let isAlive = true;
  ws.on('pong', () => { isAlive = true; });
  const alive = setInterval(() => {
    if (!isAlive) { log('tzofar no pong'); try { ws.terminate(); } catch (_) {} return; }
    isAlive = false;
    try { ws.ping(); } catch (_) {}
  }, 30000);
  ws.on('open', () => { setTzofar(true); log('tzofar connected'); });
  ws.on('message', buf => {
    try {
      const m = JSON.parse(buf.toString()); const d = m.data; if (!d) return;
      const id = m.type + '_' + d.notificationId; if (seen.has(id)) return; seen.add(id);
      if (seen.size > 500) seen.delete(seen.values().next().value);
      const a = P.tzofar(m, cityIds);
      if (a) send(a.title, a.areas, 'tzofar');
    } catch (_) {}
  });
  let closed = false;
  const again = () => { if (closed) return; closed = true; setTzofar(false); clearInterval(alive); setTimeout(connect, 5000); };
  ws.on('close', again);
  ws.on('error', () => { try { ws.terminate(); } catch (_) {} });
}

module.exports = { start: connect, PRE, END };
