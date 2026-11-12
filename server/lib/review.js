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
// הגרסה החדשה בקול אבר לכל הקלטה (נוצרת בגיטהאב ונכנסת לגיט): candidates/<אותו שם>.mp3
const CAND_DIR = path.join(__dirname, '..', 'candidates');
// מצב הקלטה: pending (ממתין) → ready (יש גרסה בקול אבר) → approved / rejected
// כמה גרסאות: candidates/<שם>_1.mp3, _2, _3 (אתה בוחר אחת), או קובץ אחד <שם>.mp3
const candFile = (r, v) => path.join(CAND_DIR, r.f.replace(/\.m4a$/, '') + (v ? '_' + v : '') + '.mp3');
const fresh = (f, r) => { try { return fs.statSync(f).mtimeMs > Math.max(r.at, r.since || 0); } catch (_) { return false; } };
function withState() {
  for (const [key, r] of Object.entries(data.recs)) {
    let st = r.state || 'pending';
    const vars = [1, 2, 3, 4, 5].filter(v => fresh(candFile(r, v), r)).length;
    r.variants = vars;
    if ((st === 'pending' || st === 'rejected') && (vars > 0 || fresh(candFile(r), r)) && !(r.rejectedAt && !newerThan(r, r.rejectedAt))) st = 'ready';
    r.state = st;
    // בשליחה לטיפול: מה קורה עם ההקלטה עכשיו
    const pr = r.prog && Date.now() - r.prog.at < 10 * 60 * 1000 ? r.prog : null;
    const msg = st === 'ready' ? `${vars || 1} גרסאות בקול אבר מוכנות – תבחר אחת בדף ההקלטה`
      : st === 'pending' ? (pr ? `${pr.p}% · ${pr.step}` : 'בתור להכנת גרסאות בקול אבר') : null;
    if (msg) for (const b of data.batches || []) for (const i of (b.items || []))
      if ((i.t === 'rec' || i.t === 'fix') && i.k === key && i.state !== 'done') { if (i.state === 'sent' || i.state === 'skipped') i.state = 'working'; i.msg = msg; }
  }
  return data;
}
// אחרי דחייה - "מוכן" רק כשיש גרסאות חדשות יותר מהדחייה
const newerThan = (r, t) => [0, 1, 2, 3, 4, 5].some(v => { try { return fs.statSync(candFile(r, v || undefined)).mtimeMs > t; } catch (_) { return false; } });
// batches: "שליחה לטיפול" מהאפליקציה - מה לטפל בו (הקלטות, הצעות שינוי, לתיקון). sent → done כשהוכנס
// tasks: דברים שהוספת ידנית לטיפול (טקסט חופשי)
// done: מה שבוצע (מפתח -> מתי) - כבר לא "לתיקון" ולא "שינוי ממתין"
const empty = () => ({ status: {}, notes: {}, edits: [], recs: {}, batches: [], tasks: [], done: {} });
let data = empty();
try { data = { ...empty(), ...JSON.parse(fs.readFileSync(FILE, 'utf8')) }; } catch (_) {}

function save() {
  try { fs.writeFileSync(FILE + '.tmp', JSON.stringify(data)); fs.renameSync(FILE + '.tmp', FILE); }
  catch (e) { log('review save failed', e.message); }
}
const str = (v, max) => String(v == null ? '' : v).replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max);

// אישור: הגרסה שבחרת נכנסת מיד במקום ההקלטה הקיימת (השרת מגיש אותה, והטלפונים מורידים)
const APP_INDEX = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'voice', 'index.json');
function targetOf(key) {
  if (/^voice\/(phrases|extra)\/[\w.-]+\.mp3$/.test(key)) return key;
  try { const rel = JSON.parse(fs.readFileSync(APP_INDEX, 'utf8'))[key]; return rel ? 'voice/' + rel : null; } catch (_) { return null; }
}
function applyRec(key) {
  const r = data.recs[key], t = targetOf(key);
  if (!r || !t) { log('review: לא נמצא לאן להכניס את ההקלטה'); return; }
  const f = candFile(r, r.chosen || undefined);
  try {
    require('./files').override(t, f); r.applied = t; r.appliedAt = Date.now(); log('review: הקלטה חדשה נכנסה - ' + t);
    // בשליחה לטיפול: ההקלטה וה"לתיקון" של אותו פריט - טופל
    for (const b of data.batches) for (const i of (b.items || [])) if ((i.t === 'rec' || i.t === 'fix') && i.k === key && i.state !== 'done') {
      i.state = 'done'; i.at = Date.now(); i.msg = 'נכנס – גרסה ' + (r.chosen || 1) + ' בקול אבר, הטלפונים מורידים אותה';
      b.state = b.items.every(x => x.state === 'done' || x.state === 'skipped') ? 'done' : 'working';
    }
    if (data.status[key] === 'fix') delete data.status[key];
    delete data.notes[key];
    data.done[key] = Date.now();
  }
  catch (e) { log('review apply failed', e.message); }
}
// פעם אחת בהפעלה: מה שאושר ועוד לא נכנס
setTimeout(() => { for (const [k, r] of Object.entries(data.recs)) if (r.state === 'approved' && !r.applied) applyRec(k); save(); }, 3000);

/** בוצע: מוריד "לתיקון" ושינויים ממתינים של המפתח, ומסמן טופל בשליחה לטיפול */
function markDone(key, msg) {
  if (data.status[key] === 'fix') delete data.status[key];
  delete data.notes[key];   // ההערה היא בקשת השינוי - כשבוצע, נמחקת
  data.edits = data.edits.filter(e => e.key !== key);
  data.done[key] = Date.now();
  for (const b of data.batches) for (const i of (b.items || [])) {
    const ik = i.t === 'edit' ? i.key : i.k;
    if (ik === key && i.state !== 'done') { i.state = 'done'; i.at = Date.now(); if (msg) i.msg = msg; }
    b.state = (b.items || []).every(x => x.state === 'done' || x.state === 'skipped') ? 'done' : b.state;
  }
}
/** פריטים שסומנו טופל בשליחה (שינוי שהוכנס, הקלטה שנכנסה) - בוצעו */
function settle() {
  for (const b of data.batches) for (const i of (b.items || [])) if (i.state === 'done') {
    const key = i.t === 'edit' ? i.key : i.k;
    if (i.t === 'edit') { data.edits = data.edits.filter(e => e.id !== i.k); data.done[key] = data.done[key] || i.at || Date.now(); }
    if ((i.t === 'fix' || i.t === 'rec') && !data.done[key]) { if (data.status[key] === 'fix') delete data.status[key]; delete data.notes[key]; data.done[key] = i.at || Date.now(); }
  }
}
setTimeout(() => { settle(); save(); }, 4000);

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
      { const t = str(op.text, 1000); const changed = (data.notes[key] || '') !== t; if (t) data.notes[key] = t; else delete data.notes[key];
        // יש הקלטה שעוד לא נבחרה לה גרסה - מכינים מחדש לפי ההערה החדשה
        const r = data.recs[key];
        if (changed && r && ['pending', 'ready', 'rejected'].includes(r.state)) { r.state = 'pending'; r.since = Date.now(); r.prog = null; r.retried = false; delete r.rejectedAt; delete r.vtext; } }
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
    case 'approve': case 'reject':
      withState();
      if (!key || !data.recs[key] || data.recs[key].state !== 'ready') return null;
      data.recs[key].state = op.op === 'approve' ? 'approved' : 'rejected';
      data.recs[key].decidedAt = Date.now();
      if (op.op === 'reject') { const r = data.recs[key]; r.rejectedAt = Date.now();
        // "אף אחת לא טובה" - מנסים שוב אוטומטית (פעם אחת); אחר כך לפי ההערה שתשנה
        if (!r.retried) { r.state = 'pending'; r.since = Date.now(); r.prog = null; r.retried = true; delete r.vtext; } }
      if (op.op === 'approve' && op.v) data.recs[key].chosen = Math.max(1, Math.min(5, op.v | 0));
      if (op.op === 'approve') applyRec(key);
      log('review: ' + (op.op === 'approve' ? 'אושרה' : 'נדחתה') + ' גרסה בקול אבר');
      break;
    case 'delrec':
      if (!key || !data.recs[key]) return null;
      try { fs.unlinkSync(path.join(REC_DIR, data.recs[key].f)); } catch (_) {}
      delete data.recs[key];
      break;
    case 'task':
      { const t = str(op.text, 1000); if (!t || data.tasks.length >= 500) return null;
        data.tasks.push({ id: crypto.randomBytes(5).toString('hex'), text: t, at: Date.now() }); }
      break;
    case 'deltask':
      { const n = data.tasks.length; data.tasks = data.tasks.filter(t => t.id !== str(op.id, 20)); if (n === data.tasks.length) return null; }
      break;
    case 'submit': {
      withState();
      // רק מה שסימנת (אם נשלחה רשימה), ורק מה שעדיין קיים
      const pick = (all, sel) => Array.isArray(sel) ? all.filter(x => sel.includes(x)) : all;
      const recs = pick(Object.entries(data.recs).filter(([, r]) => r.state === 'pending' || r.state === 'rejected').map(([k]) => k), op.recs);
      const edits = pick(data.edits.map(e => e.id), op.edits);
      const fix = pick(Object.entries(data.status).filter(([, v]) => v === 'fix').map(([k]) => k), op.fix);
      const tasks = pick(data.tasks.map(t => t.id), op.tasks);
      if (!recs.length && !edits.length && !fix.length && !tasks.length) return null;
      // סטטוס לכל פריט: sent נשלח · working בטיפול · done טופל · skipped לא בוצע (עם הסבר)
      const items = [
        ...recs.map(k => ({ t: 'rec', k })),
        ...edits.map(id => { const e = data.edits.find(x => x.id === id); return { t: 'edit', k: id, key: e.key, field: e.field, from: e.from, to: e.to }; }),
        ...fix.map(k => ({ t: 'fix', k, note: data.notes[k] || '' })),
        ...tasks.map(id => ({ t: 'task', k: id, text: (data.tasks.find(x => x.id === id) || {}).text || '' })),
      ].map(i => ({ ...i, state: 'sent', at: Date.now() }));
      data.batches.push({ id: crypto.randomBytes(4).toString('hex'), at: Date.now(), state: 'sent', recs, edits, fix, tasks, items });
      data.tasks = data.tasks.filter(t => !tasks.includes(t.id));   // מה שנוסף ידנית עובר לשליחה
      // הקלטה שנשלחה לטיפול (גם אחרי "אף אחת לא טובה") - מכינים גרסאות חדשות מיד
      for (const k of recs.concat(fix)) { const r = data.recs[k];
        if (r && ['pending', 'ready', 'rejected'].includes(r.state)) { r.state = 'pending'; r.since = Date.now(); r.prog = null; r.retried = false; delete r.rejectedAt; delete r.vtext; } }
      data.batches = data.batches.slice(-50);
      log(`review: נשלח לטיפול - ${recs.length} הקלטות, ${edits.length} שינויים, ${fix.length} לתיקון, ${tasks.length} ידני`);
      break;
    }
    case 'itemstate': {   // עדכון התקדמות לפריט (ממני, כשאני מטפל)
      const b = data.batches.find(x => x.id === str(op.id, 20)); if (!b || !b.items) return null;
      const it = b.items.find(i => i.t === op.t && i.k === op.k); if (!it) return null;
      if (!['sent', 'working', 'done', 'skipped'].includes(op.state)) return null;
      it.state = op.state; it.at = Date.now(); if (op.msg != null) it.msg = str(op.msg, 500);
      b.state = b.items.every(i => i.state === 'done' || i.state === 'skipped') ? 'done' : b.items.some(i => i.state !== 'sent') ? 'working' : 'sent';
      if (b.state === 'done') b.doneAt = Date.now();
      settle();
      break;
    }
    case 'batchdone': {
      const b = data.batches.find(x => x.id === str(op.id, 20)); if (!b) return null;
      b.state = 'done'; b.doneAt = Date.now(); b.summary = str(op.summary, 2000);
      break;
    }
    case 'markdone':
      if (!key) return null;
      markDone(key, 'סומן כבוצע');
      break;
    case 'resetkey': {   // איפוס של פריט אחד: כל הסימונים, ההערה, ההקלטה, השינויים והשליחות שלו
      if (!key) return null;
      for (const k of [key, 'list:' + key, 'list-d:' + key, 'district:' + key]) delete data.status[k];
      delete data.notes[key]; delete data.done[key];
      if (data.recs[key]) { try { fs.unlinkSync(path.join(REC_DIR, data.recs[key].f)); } catch (_) {} delete data.recs[key]; }
      const ids = new Set(data.edits.filter(e => e.key === key).map(e => e.id));
      data.edits = data.edits.filter(e => e.key !== key);
      for (const b of data.batches) if (b.items) b.items = b.items.filter(i => !(i.k === key || i.key === key || ids.has(i.k)));
      data.batches = data.batches.filter(b => !b.items || b.items.length);
      log('review: אופס פריט');
      break;
    }
    case 'resetsend':   // איפוס שליחה לטיפול: כל השליחות והסטטוסים שלהן, ומה שנוסף ידנית
      data.batches = []; data.tasks = [];
      log('review: שליחה לטיפול אופסה');
      break;
    case 'reset':   // איפוס מלא של ניהול צופר: סימונים, הערות, שינויים, הקלטות, שליחות - הכל מההתחלה
      if (op.confirm !== 'RESET') return null;
      try { for (const f of fs.readdirSync(REC_DIR)) fs.unlinkSync(path.join(REC_DIR, f)); } catch (_) {}
      data = empty(); shares.clear();
      log('review: ניהול צופר אופס');
      break;
    case 'import':   // מעבר מגרסה קודמת: הסימונים שהיו רק בטלפון
      for (const k of Array.isArray(op.fix) ? op.fix.slice(0, 3000) : []) { const s = str(k, 200); if (s && !data.status[s]) data.status[s] = 'fix'; }
      break;
    default: return null;
  }
  save();
  return withState();
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
      data.recs[key] = { f, at: Date.now(), state: 'pending' }; save();
      log('review: הקלטת הגייה נשמרה');
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(withState()));
    } catch (e) { log('review rec save failed', e.message); res.writeHead(500); res.end(); }
  });
}

// קישור חד-פעמי להקלטה (15 דקות, הורדה אחת) - כדי שהבנייה בגיטהאב תוכל לנתח אותה בלי קוד הכניסה
const shares = new Map();   // טוקן -> { f, until }
function share(res, u) {
  const r = data.recs[str(u.searchParams.get('key'), 200)];
  if (!r) { res.writeHead(404); return res.end(); }
  const t = crypto.randomBytes(16).toString('hex');
  shares.set(t, { f: r.f, until: Date.now() + 15 * 60 * 1000 });
  res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ path: '/rec-pub/' + t, name: r.f.replace(/\.m4a$/, '') }));
}
function pub(res, t) {
  const s = shares.get(t); shares.delete(t);
  if (!s || Date.now() > s.until) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': 'audio/mp4' });
  fs.createReadStream(path.join(REC_DIR, s.f)).on('error', () => res.end()).pipe(res);
}
/** הגרסה החדשה בקול אבר */
function cand(res, u) {
  const r = data.recs[str(u.searchParams.get('key'), 200)];
  if (!r) { res.writeHead(404); return res.end(); }
  const v = Math.max(0, Math.min(5, +u.searchParams.get('v') || 0)) || r.chosen || 0;
  res.writeHead(200, { 'Content-Type': 'audio/mpeg' });
  fs.createReadStream(candFile(r, v || undefined)).on('error', () => res.end()).pipe(res);
}

// ---- יצירת גרסאות בקול אבר אוטומטית: בנייה מתוזמנת בגיטהאב (voice-auto) לוקחת עבודות מכאן ומעלה את הקבצים ----
const TOOLS = path.join(__dirname, '..', '..', 'tools');
function textsFor(key) {
  const read = f => { try { return fs.readFileSync(path.join(TOOLS, f), 'utf8'); } catch (_) { return ''; } };
  let nik = null, plain = key;
  const m = /^voice\/(phrases|extra)\/([\w]+)\.mp3$/.exec(key);
  if (m) {
    if (m[1] === 'extra') { try { nik = JSON.parse(read('extra_phrases.json'))[m[2]]; } catch (_) {} }
    else { const t = /TEXT = \{([\s\S]*?)\n\}/.exec(read('gen_voice.py')); if (t) { const r = new RegExp('"' + m[2] + '":\\s*"([^"]+)"').exec(t[1]); if (r) nik = r[1]; } }
    plain = nik ? nik.replace(/[\u0591-\u05C7]/g, '') : null;
  } else {
    try { nik = JSON.parse(read('pronounce.json'))[key] || null; } catch (_) {}
    try { nik = JSON.parse(read('pronounce_manual.json'))[key] || nik; } catch (_) {}
    plain = key.replace(/ - /g, ' ').replace(/-/g, ' ');
  }
  if (!plain && !nik) return null;
  // ההערה שלך (איך להגות) קודמת לכל: 1 - כמו שכתבת · 2 - כמו שכתבת, לאט · 3 - כמו שכתבת בלי ניקוד (אם יש)
  const note = str(data.notes[key], 200);
  if (note) {
    const bare = note.replace(/[\u0591-\u05C7]/g, '');
    return [{ text: note, rate: '+12%' }, { text: note, rate: '+0%' }, { text: bare !== note ? bare : (nik || plain), rate: '+12%' }];
  }
  // 1: הניקוד של היום · 2: בלי ניקוד · 3: הניקוד, לאט יותר
  return [{ text: nik || plain, rate: '+12%' }, { text: plain || nik, rate: '+12%' }, { text: nik || plain, rate: '+0%' }];
}
function jobs(res) {
  withState();
  const out = [];
  for (const [k, r] of Object.entries(data.recs)) {
    if (r.state !== 'pending' && !(r.state === 'rejected' && !r.retried)) continue;
    const texts = textsFor(k); if (!texts) continue;
    if (!r.upTok) { r.upTok = crypto.randomBytes(12).toString('hex'); save(); }
    out.push({ token: r.upTok, name: r.f.replace(/\.m4a$/, ''), texts, key: k, note: str(data.notes[k], 200), nik: texts[0].text, plain: texts[1].text });
  }
  res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(out));
}
/** התקדמות חיה מהבנייה בגיטהאב: אחוז ושלב לכל הקלטה */
function progress(res, u) {
  const tok = String(u.searchParams.get('t') || '');
  const e = Object.entries(data.recs).find(([, r]) => r.upTok && r.upTok === tok);
  if (!e) { res.writeHead(404); return res.end(); }
  e[1].prog = { p: Math.max(0, Math.min(100, +u.searchParams.get('p') || 0)), step: str(u.searchParams.get('s'), 100), at: Date.now() };
  if (u.searchParams.get('h')) e[1].heard = str(u.searchParams.get('h'), 200);   // מה פוענח מההקלטה שלך
  save(); res.writeHead(200); res.end('ok');
}
/** ההקלטה שלך - לפענוח בבנייה בגיטהאב (עם המפתח החד-פעמי של ההקלטה) */
function recFor(res, u) {
  const tok = String(u.searchParams.get('t') || '');
  const e = Object.entries(data.recs).find(([, r]) => r.upTok && r.upTok === tok);
  if (!e) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': 'audio/mp4' });
  fs.createReadStream(path.join(REC_DIR, e[1].f)).on('error', () => res.end()).pipe(res);
}
function upload(req, res, u) {
  const tok = String(u.searchParams.get('t') || ''), v = Math.max(1, Math.min(5, +u.searchParams.get('v') || 1));
  const e = Object.entries(data.recs).find(([, r]) => r.upTok && r.upTok === tok);
  if (!e || req.method !== 'POST') { res.writeHead(404); return res.end(); }
  const bufs = []; let n = 0, big = false;
  req.on('data', d => { n += d.length; if (n > 400 * 1024) { big = true; req.destroy(); } else bufs.push(d); });
  req.on('end', () => {
    const b = Buffer.concat(bufs);
    if (big || n < 500 || !(b[0] === 0xff || b.slice(0, 3).toString() === 'ID3')) { res.writeHead(400); return res.end(); }
    fs.mkdirSync(CAND_DIR, { recursive: true });
    fs.writeFileSync(candFile(e[1], v), b);
    e[1].vtext = e[1].vtext || {}; e[1].vtext[v] = str(u.searchParams.get('txt'), 200);   // מאיזה טקסט נוצרה הגרסה
    if (e[1].state === 'rejected') { e[1].state = 'pending'; e[1].retried = true; }
    save(); log('review: גרסה ' + v + ' בקול אבר נוצרה אוטומטית');
    res.writeHead(200); res.end('ok');
  });
}

function handle(req, res, u) {
  if (u && u.pathname === '/api/review/rec') return rec(req, res, u);
  if (u && u.pathname === '/api/review/share') return share(res, u);
  if (u && u.pathname === '/api/review/cand') return cand(res, u);
  const send = (code, o) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
  if (req.method === 'GET') return send(200, withState());
  if (req.method !== 'POST') return send(405, {});
  let body = '';
  req.on('data', d => { body += d; if (body.length > 200000) req.destroy(); });
  req.on('end', () => {
    let op; try { op = JSON.parse(body); } catch (_) { return send(400, {}); }
    const r = apply(op || {});
    return r ? send(200, r) : send(400, {});
  });
}

module.exports = { handle, pub, jobs, upload, progress, recFor };
