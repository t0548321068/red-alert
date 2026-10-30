// שליחה לטלפונים דרך Firebase: התרעות (נושא alerts), סימן חיים, ותקלות למכשירי הבטא (נושא admin).
// מפתח השרת: הקובץ service-account.json בתיקיית server (לא נכנס לגיט).
const fs = require('fs');
const path = require('path');
const admin = require('firebase-admin');
const { log } = require('./util');
const web = require('./web');

admin.initializeApp({ credential: admin.credential.cert(require(path.join(__dirname, '..', 'service-account.json'))) });
const TOPIC = 'alerts';

// ---- מה כבר נשלח: נשמר בקובץ, כך שאחרי הפעלה מחדש (עדכון עצמי) לא שולחים שוב את אותה התרעה ----
// אותה התרעה מכמה מקורות נשלחת פעם אחת: יישוב שכבר נשלח עם אותה כותרת ב-2 הדקות האחרונות - לא שוב
const STATE_FILE = path.join(__dirname, '..', 'state.json');
const DEDUP_MS = 2 * 60 * 1000;
const sentAt = new Map();
const state = { orefId: '' };   // מזהה ההתרעה האחרונה בקובץ החי של פיקוד העורף
try {
  const s = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
  for (const [k, t] of s.sent || []) if (Date.now() - t < DEDUP_MS) sentAt.set(k, t);
  state.orefId = s.orefId || '';
} catch (_) {}
let saveTimer = null;
function saveState() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    fs.writeFile(STATE_FILE + '.tmp', JSON.stringify({ sent: [...sentAt], orefId: state.orefId }), e => {
      if (!e) fs.rename(STATE_FILE + '.tmp', STATE_FILE, () => {});
    });
  }, 1000);
}

async function sendAll(msgs) {
  let ok = true;
  for (const m of msgs) { try { await admin.messaging().send(m); } catch (e) { ok = false; log('send failed', e.message); } }
  return ok;
}

// כשל רצוף בשליחה - הודעה למכשירי הבטא (לכל היותר פעם בחצי שעה)
let failsInRow = 0, failAlertAt = 0;
function sendResult(ok) {
  if (ok) { failsInRow = 0; return; }
  if (++failsInRow >= 3 && Date.now() - failAlertAt > 30 * 60 * 1000) {
    failAlertAt = Date.now();
    adminPush('⚠️ השרת לא מצליח לשלוח התרעות דרך Firebase');
  }
}

// הודעת Firebase מוגבלת ל-4KB - התרעה עם הרבה אזורים (כל הארץ) נשלחת בכמה חלקים
async function send(title, areas, src, at = Date.now()) {
  const now = Date.now();
  for (const [k, t] of sentAt) if (now - t > DEDUP_MS) sentAt.delete(k);
  areas = [...new Set(areas.map(a => String(a).trim()).filter(Boolean))].filter(a => !sentAt.has(title + '|' + a));
  if (!areas.length) return;
  const keys = areas.map(a => title + '|' + a);
  web.broadcast({ title, areas, src, at, sent: now });   // לאתר - בזמן אמת
  for (const k of keys) sentAt.set(k, now);   // בזיכרון מיד; בקובץ רק אחרי שליחה מוצלחת
  const chunks = [];
  let cur = [];
  for (const a of areas) {
    if (Buffer.byteLength(JSON.stringify([...cur, a])) > 3000) { chunks.push(cur); cur = []; }
    cur.push(a);
  }
  if (cur.length) chunks.push(cur);
  const msgs = chunks.map(part => ({
    topic: TOPIC,
    data: { type: 'alert', title, areas: part.join('|'), src, at: String(at) },
    android: { priority: 'high', ttl: 60 * 1000 },
  }));
  const first = await sendAll(msgs);
  if (first) saveState();
  log('sent', src, title, areas.length + ' areas', chunks.length + ' msg', first ? '' : '(failed)');
  // אמינות: אותה הודעה שוב אחרי 5 שניות - אם הראשונה לא הגיעה. הטלפון מסנן כפילויות (אותו זמן התרעה)
  setTimeout(async () => {
    const second = await sendAll(msgs);
    // שתי השליחות נכשלו - משחררים את היישובים, כדי שאותה התרעה ממקור אחר כן תישלח
    if (!first && !second) { for (const k of keys) sentAt.delete(k); saveState(); }
    else if (!first) saveState();
    sendResult(first || second);
  }, 5000);
}

// ---- סימן חיים: מצב המקורות + גרסת קבצי הנתונים.
// עדיפות גבוהה: בעדיפות רגילה טלפון במצב שינה עמוקה (בלילה) מקבל אותו רק אחרי שעות,
// ואז חושב שהשרת נפל ועובר לבדוק בעצמו - שורף סוללה ----
async function heartbeat(up, dv) {
  try {
    await admin.messaging().send({
      topic: TOPIC, data: { type: 'hb', up: up.join(','), at: String(Date.now()), dv },
      android: { priority: 'high', ttl: 5 * 60 * 1000 },
    });
  } catch (e) { log('hb failed', e.message); }
}

// גרסה חדשה פורסמה
function updatePush(latest) {
  return admin.messaging().send({ topic: TOPIC, data: { type: 'update', stable: latest.stable, beta: latest.beta },
    android: { priority: 'high', ttl: 6 * 3600 * 1000 } }).catch(e => log('update push failed', e.message));
}

// תקלה - רק למכשירי הבטא
function adminPush(text) {
  log('admin', text);
  admin.messaging().send({ topic: 'admin', data: { type: 'admin', text }, android: { priority: 'high', ttl: 3600 * 1000 } })
    .catch(e => log('admin push failed', e.message));
}

// הודעה מהאתר: לכל הטלפונים, או למכשיר אחד (נושא d_<קוד המכשיר>)
function message(text, to) {
  const topic = to === 'all' ? TOPIC : 'd_' + to;
  log('message', topic, text);
  return admin.messaging().send({ topic, data: { type: 'msg', text }, android: { priority: 'high', ttl: 24 * 3600 * 1000 } });
}

module.exports = { send, heartbeat, updatePush, adminPush, message, state, saveState };
