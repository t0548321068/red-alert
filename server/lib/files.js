// קבצים שהטלפונים מקבלים מהשרת:
// - data/: הגדרות, זמני מרחב מוגן, יישובים, הקלטות מתוקנות. האפליקציה מורידה רק מה שהשתנה, ומעדיפה אותם
//   על מה שארוז בה (אותם שמות נתיב כמו בתוך האפליקציה)
// - beta_devices.txt: המכשירים שמקבלים גרסאות בטא והודעות תקלה - מוסיפים מכשיר בלי לעדכן את האפליקציה
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DATA_DIR = path.join(__dirname, '..', 'data');
// הקלטות היישובים (voice/areas - כ-1,450 קבצים): ברשימה נפרדת, כדי שהרשימה שהטלפונים בודקים כל שעה תישאר קטנה.
// ברשימה הראשית רק "voice" - גרסת ההקלטות; הטלפון מוריד את /data/voice.json רק כשהיא משתנה
const VOICE_PREFIX = 'voice/areas/';
// הקלטות שאישרת באפליקציית ניהול צופר (גרסה בקול אבר) - מעל data, בלי לגעת בגיט (לא נכנס לגיט)
const OVERRIDE_DIR = path.join(__dirname, '..', 'voice-overrides');
const dataIndex = { v: '', voice: '', files: {} };
const voiceIndex = {};
const src = {};   // נתיב -> הקובץ בדיסק (data או voice-overrides)
function scan() {   // בהפעלה, ושוב אחרי אישור הקלטה
  const files = {};
  for (const k of Object.keys(src)) delete src[k];
  const walk = (dir, rel) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name.startsWith('.')) continue;
      const p = path.join(dir, e.name), r = rel ? rel + '/' + e.name : e.name;
      if (e.isDirectory()) walk(p, r);
      else { files[r] = crypto.createHash('sha1').update(fs.readFileSync(p)).digest('hex').slice(0, 12); src[r] = p; }
    }
  };
  try { walk(DATA_DIR, ''); } catch (_) {}
  try { walk(OVERRIDE_DIR, ''); } catch (_) {}
  for (const k of Object.keys(voiceIndex)) delete voiceIndex[k];
  for (const k of Object.keys(files)) if (k.startsWith(VOICE_PREFIX)) { voiceIndex[k] = files[k]; delete files[k]; }
  dataIndex.files = files;
  dataIndex.voice = crypto.createHash('sha1').update(JSON.stringify(voiceIndex)).digest('hex').slice(0, 12);
  dataIndex.v = crypto.createHash('sha1').update(JSON.stringify(files) + dataIndex.voice).digest('hex').slice(0, 12);
}
scan();

/** הקלטה מאושרת: נכנסת במקום ההקלטה הקיימת (rel כמו בתוך האפליקציה, למשל voice/areas/avigdor.mp3) */
function override(rel, fromFile) {
  if (!/^voice\/[\w/.-]+\.mp3$/.test(rel) || rel.includes('..')) throw new Error('bad path');
  const dst = path.join(OVERRIDE_DIR, rel);
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  fs.copyFileSync(fromFile, dst + '.tmp'); fs.renameSync(dst + '.tmp', dst);
  scan();
}

function serveData(res, rel) {
  if (!rel) { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify(dataIndex)); }
  if (rel === 'voice.json') { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify(voiceIndex)); }
  // רק קבצים מהרשימות - בלי גישה לשאר השרת
  if (!Object.hasOwn(dataIndex.files, rel) && !Object.hasOwn(voiceIndex, rel)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': 'application/octet-stream' });
  fs.createReadStream(src[rel] || path.join(DATA_DIR, rel)).on('error', () => res.end()).pipe(res);
}

// עונים רק כן/לא למכשיר ששאל
function serveBeta(res, code) {
  let list = [];
  try { list = fs.readFileSync(path.join(__dirname, '..', 'beta_devices.txt'), 'utf8').split('\n').map(l => l.trim().toUpperCase()).filter(l => l && !l.startsWith('#')); } catch (_) {}
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ allowed: /^[0-9A-F]{8}$/.test(code) && list.includes(code) }));
}

/** קובץ נתונים שמשתנה מניהול צופר (מחוזות / זמני מיגון) - נכנס מיד, בלי גיט */
function overrideText(rel, text) {
  if (!['districts.json', 'migun.txt'].includes(rel)) throw new Error('bad path');
  const dst = path.join(OVERRIDE_DIR, rel);
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  fs.writeFileSync(dst + '.tmp', text); fs.renameSync(dst + '.tmp', dst);
  scan();
}
/** התוכן הנוכחי של קובץ נתונים: מה שהשרת מגיש, ואם אין - מתוך האפליקציה */
function currentText(rel) {
  if (src[rel]) return fs.readFileSync(src[rel], 'utf8');
  return fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', rel), 'utf8');
}

module.exports = { dataIndex, voiceIndex, serveData, serveBeta, override, overrideText, currentText };
