// קבצים שהטלפונים מקבלים מהשרת:
// - data/: הגדרות, זמני מרחב מוגן, יישובים, הקלטות מתוקנות. האפליקציה מורידה רק מה שהשתנה, ומעדיפה אותם
//   על מה שארוז בה (אותם שמות נתיב כמו בתוך האפליקציה)
// - beta_devices.txt: המכשירים שמקבלים גרסאות בטא והודעות תקלה - מוסיפים מכשיר בלי לעדכן את האפליקציה
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DATA_DIR = path.join(__dirname, '..', 'data');
const dataIndex = { v: '', files: {} };
(function scan() {   // השרת עולה מחדש בכל עדכון עצמי - מספיק לסרוק בהפעלה
  const files = {};
  const walk = (dir, rel) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name.startsWith('.')) continue;
      const p = path.join(dir, e.name), r = rel ? rel + '/' + e.name : e.name;
      if (e.isDirectory()) walk(p, r);
      else files[r] = crypto.createHash('sha1').update(fs.readFileSync(p)).digest('hex').slice(0, 12);
    }
  };
  try { walk(DATA_DIR, ''); } catch (_) {}
  dataIndex.files = files;
  dataIndex.v = crypto.createHash('sha1').update(JSON.stringify(files)).digest('hex').slice(0, 12);
})();

function serveData(res, rel) {
  if (!rel) { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify(dataIndex)); }
  // רק קבצים מהרשימה - בלי גישה לשאר השרת
  if (!Object.hasOwn(dataIndex.files, rel)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': 'application/octet-stream' });
  fs.createReadStream(path.join(DATA_DIR, rel)).on('error', () => res.end()).pipe(res);
}

// עונים רק כן/לא למכשיר ששאל
function serveBeta(res, code) {
  let list = [];
  try { list = fs.readFileSync(path.join(__dirname, '..', 'beta_devices.txt'), 'utf8').split('\n').map(l => l.trim().toUpperCase()).filter(l => l && !l.startsWith('#')); } catch (_) {}
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ allowed: /^[0-9A-F]{8}$/.test(code) && list.includes(code) }));
}

module.exports = { dataIndex, serveData, serveBeta };
