// בדיקת היישובים וההקראה מאפליקציית "ניהול צופר" (רק לך, עם קוד הכניסה של האתר):
// - status: מה נבדק ותקין (ok) ומה צריך תיקון (fix), לכל יישוב / הקלטה
// - notes: הערה חופשית לכל יישוב / הקלטה
// - edits: הצעות שינוי (שם, מחוז, זמן מיגון...) - ממתינות לאישור. לא נכנסות לטלפונים מכאן;
//   מכניסים אותן לנתונים ידנית אחרי בדיקה (שם שונה מפיקוד העורף = יישוב שלא יקבל התרעות)
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { log } = require('./util');

const FILE = path.join(__dirname, '..', 'review.json');   // לא נכנס לגיט
const FIELDS = new Set(['name', 'district', 'shelter', 'other']);
const REC_DIR = path.join(__dirname, '..', 'recordings');   // הקלטות ההגייה שלך (לא נכנס לגיט)
const empty = () => ({ status: {}, notes: {}, edits: [], recs: {} });
let data = empty();
try { data = { ...empty(), ...JSON.parse(fs.readFileSync(FILE, 'utf8')) }; } catch (_) {}

function save() {
  try { fs.writeFileSync(FILE + '.tmp', JSON.stringify(data)); fs.renameSync(FILE + '.tmp', FILE); }
  catch (e) { log('review save failed', e.message); }
}
const str = (v, max) => String(v == null ? '' : v).replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max);

/** פעולה אחת מהאפליקציה. מחזיר את המצב המלא, או null אם הבקשה לא תקינה */
function apply(op) {
  const key = str(op.key, 200);
  switch (op.op) {
    case 'status':
      if (!key) return null;
      if (op.value === 'ok' || op.value === 'fix') data.status[key] = op.value; else delete data.status[key];
      break;
    case 'note':
      if (!key) return null;
      { const t = str(op.text, 1000); if (t) data.notes[key] = t; else delete data.notes[key]; }
      break;
    case 'edit':
      if (!key || !FIELDS.has(op.field)) return null;
      if (data.edits.length >= 2000) return null;
      data.edits.push({ id: crypto.randomBytes(6).toString('hex'), key, field: op.field,
        from: str(op.from, 200), to: str(op.to, 200), at: Date.now(), state: 'pending' });
      break;
    case 'cancel':
      { const n = data.edits.length; data.edits = data.edits.filter(e => e.id !== str(op.id, 20)); if (n === data.edits.length) return null; }
      break;
    case 'delrec':
      if (!key || !data.recs[key]) return null;
      try { fs.unlinkSync(path.join(REC_DIR, data.recs[key].f)); } catch (_) {}
      delete data.recs[key];
      break;
    case 'import':   // מעבר מגרסה קודמת: הסימונים שהיו רק בטלפון
      for (const k of Array.isArray(op.fix) ? op.fix.slice(0, 3000) : []) { const s = str(k, 200); if (s && !data.status[s]) data.status[s] = 'fix'; }
      break;
    default: return null;
  }
  save();
  return data;
}

const recName = key => crypto.createHash('sha1').update(key).digest('hex').slice(0, 16) + '.m4a';

/** /api/review/rec?key=... - הקלטת הגייה: POST שומר (עד 3MB), GET משמיע */
function rec(req, res, u) {
  const key = str(u.searchParams.get('key'), 200);
  if (!key) { res.writeHead(400); return res.end(); }
  if (req.method === 'GET') {
    const r = data.recs[key]; if (!r) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': 'audio/mp4' });
    return fs.createReadStream(path.join(REC_DIR, r.f)).on('error', () => res.end()).pipe(res);
  }
  if (req.method !== 'POST') { res.writeHead(405); return res.end(); }
  const bufs = []; let n = 0, big = false;
  req.on('data', d => { n += d.length; if (n > 3 * 1024 * 1024) { big = true; req.destroy(); } else bufs.push(d); });
  req.on('end', () => {
    if (big || !n) { res.writeHead(400); return res.end(); }
    try {
      fs.mkdirSync(REC_DIR, { recursive: true });
      const f = recName(key);
      fs.writeFileSync(path.join(REC_DIR, f + '.tmp'), Buffer.concat(bufs)); fs.renameSync(path.join(REC_DIR, f + '.tmp'), path.join(REC_DIR, f));
      data.recs[key] = { f, at: Date.now() }; save();
      log('review: הקלטת הגייה נשמרה');
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(data));
    } catch (e) { log('review rec save failed', e.message); res.writeHead(500); res.end(); }
  });
}

function handle(req, res, u) {
  if (u && u.pathname === '/api/review/rec') return rec(req, res, u);
  const send = (code, o) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
  if (req.method === 'GET') return send(200, data);
  if (req.method !== 'POST') return send(405, {});
  let body = '';
  req.on('data', d => { body += d; if (body.length > 200000) req.destroy(); });
  req.on('end', () => {
    let op; try { op = JSON.parse(body); } catch (_) { return send(400, {}); }
    const r = apply(op || {});
    return r ? send(200, r) : send(400, {});
  });
}

module.exports = { handle };
