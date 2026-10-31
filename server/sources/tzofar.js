// צופר: חיבור push (websocket) - ההתרעה מגיעה מיד
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const { log } = require('../lib/util');
const { send } = require('../lib/push');
const { setTzofar } = require('../lib/health');

const THREATS = { 0: 'ירי רקטות וטילים', 1: 'אירוע חומרים מסוכנים', 2: 'חדירת מחבלים', 3: 'רעידת אדמה', 4: 'חשש לצונאמי',
  5: 'חדירת כלי טיס עוין', 6: 'חשש לאירוע רדיולוגי', 7: 'חשש לאירוע כימי', 8: 'התרעות פיקוד העורף' };
const PRE = 'בדקות הקרובות צפויות להתקבל התרעות באזורך', END = 'האירוע הסתיים';

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
      if (m.type === 'ALERT' && !d.isDrill && THREATS[d.threat ?? 0] && Array.isArray(d.cities)) {
        send(THREATS[d.threat ?? 0], d.cities, 'tzofar');
      } else if (m.type === 'SYSTEM_MESSAGE' && (d.instructionType === 0 || d.instructionType === 1)) {
        const areas = (d.citiesIds || []).map(i => cityIds[i]).filter(Boolean);
        if (areas.length) send(d.instructionType === 0 ? PRE : END, areas, 'tzofar');
      }
    } catch (_) {}
  });
  let closed = false;
  const again = () => { if (closed) return; closed = true; setTzofar(false); clearInterval(alive); setTimeout(connect, 5000); };
  ws.on('close', again);
  ws.on('error', () => { try { ws.terminate(); } catch (_) {} });
}

module.exports = { start: connect, PRE, END };
