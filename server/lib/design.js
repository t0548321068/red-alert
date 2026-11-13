// "עריכת צופר": שינויי נראות ששלחת מהאפליקציה (צבע, גודל, טקסט, מיקום, הערה לכל רכיב).
// כל שליחה = batch אחד. מצבים: sent (נשלח) → working (בטיפול) → done (בוצע).
// GET/POST /api/design - מהאפליקציה (עם הכניסה של האתר).
// GET /design-inbox - לבנייה בגיטהאב (רק קריאה של מה שעוד לא בוצע), POST /design-state - עדכון מצב עם המפתח של השליחה
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { log } = require('./util');

const FILE = path.join(__dirname, '..', 'design.json');   // לא נכנס לגיט
let data = { batches: [] };
try { data = { batches: [], ...JSON.parse(fs.readFileSync(FILE, 'utf8')) }; } catch (_) {}

function save() {
  try { fs.writeFileSync(FILE + '.tmp', JSON.stringify(data)); fs.renameSync(FILE + '.tmp', FILE); }
  catch (e) { log('design save failed', e.message); }
}
const str = (v, max) => String(v == null ? '' : v).replace(/[\u0000-\u0009\u000b-\u001f]/g, ' ').slice(0, max);

/** פריט אחד מהאפליקציה - רק השדות המוכרים, באורך מוגבל */
function clean(i) {
  const o = { screen: str(i.screen, 120), screenName: str(i.screenName, 120), el: str(i.el, 200), label: str(i.label, 120),
    kind: str(i.kind, 4), theme: str(i.theme, 6), changes: {} };
  const c = i.changes || {};
  for (const k of ['color', 'size', 'text', 'radius']) if (c[k] && typeof c[k] === 'object')
    o.changes[k] = { from: str(c[k].from, 400), to: str(c[k].to, 400) };
  if (c.move && typeof c.move === 'object') o.changes.move = { dx: +c.move.dx || 0, dy: +c.move.dy || 0 };
  if (c.note) o.changes.note = str(c.note, 6000);
  // תמונה קטנה של הרכיב (JPEG ב-base64) - לזיהוי
  if (typeof i.shot === 'string' && /^[A-Za-z0-9+/=]+$/.test(i.shot) && i.shot.length <= 150000) o.shot = i.shot;
  return o;
}

/** מה שהאפליקציה מקבלת: השליחות האחרונות, בלי המפתח */
const view = () => ({ batches: data.batches.slice(-10).map(({ tok, ...b }) => b) });

function handle(req, res) {
  const send = (code, o) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
  if (req.method === 'GET') return send(200, view());
  if (req.method !== 'POST') return send(405, {});
  let body = '';
  req.on('data', d => { body += d; if (body.length > 8000000) req.destroy(); });
  req.on('end', () => {
    let op; try { op = JSON.parse(body); } catch (_) { return send(400, {}); }
    if (op && op.op === 'send' && Array.isArray(op.items) && op.items.length && op.items.length <= 300) {
      data.batches.push({ id: crypto.randomBytes(4).toString('hex'), tok: crypto.randomBytes(12).toString('hex'),
        at: Date.now(), state: 'sent', msg: '', items: op.items.map(clean) });
      data.batches = data.batches.slice(-50);
      save(); log('design: שליחה חדשה - ' + op.items.length + ' שינויים');
      return send(200, view());
    }
    return send(400, {});
  });
}

/** לגיטהאב: כל השליחות שעוד לא בוצעו, עם המפתח לעדכון המצב */
function inbox(res) {
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(data.batches.filter(b => b.state !== 'done'), null, 1));
}

/** עדכון מצב שליחה: ?id=&t=&state=working|done&msg= */
function setState(req, res, u) {
  const b = data.batches.find(x => x.id === str(u.searchParams.get('id'), 20));
  const st = u.searchParams.get('state');
  if (req.method !== 'POST' || !b || u.searchParams.get('t') !== b.tok || !['sent', 'working', 'done'].includes(st)) {
    res.writeHead(404); return res.end();
  }
  b.state = st; b.msg = str(u.searchParams.get('msg'), 300); if (st === 'done') b.doneAt = Date.now();
  save(); log('design: שליחה ' + b.id + ' - ' + st);
  res.writeHead(200); res.end('ok');
}

module.exports = { handle, inbox, setState };
