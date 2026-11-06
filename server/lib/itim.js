// זמני שבת וחג אמיתיים מ"עתים לבינה", פעם בשבוע: במוצאי שבת ב-22:00 (שעון ישראל) - לכל 125 המקומות,
// הכניסות והיציאות של 11 הימים הבאים (כך גם חג באמצע השבוע נתפס). נשמר ב-shabbat_week.json.
// ישירות מהשרת; אם האתר חוסם - דרך Cloudflare (/itim). בלי קובץ עדכני - הנוסחה (data/shabbat.json)
const fs = require('fs');
const path = require('path');
const { get, log } = require('./util');

const FILE = path.join(__dirname, '..', 'shabbat_week.json');
const CF = 'https://oref-proxy.t0548321068.workers.dev/itim?p=';
const H = { Accept: 'application/json', Referer: 'https://itimlabina.co.il/',
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36' };
const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'], MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const fmt = ms => { const d = new Date(ms); return `${DAYS[d.getUTCDay()]},%20${String(d.getUTCDate()).padStart(2, '0')}%20${MON[d.getUTCMonth()]}%20${d.getUTCFullYear()}%2000:00:00%20GMT`; };
const ilParts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Jerusalem', hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', weekday: 'short', hour: 'numeric' });
const il = ms => { const p = Object.fromEntries(ilParts.formatToParts(new Date(ms)).map(x => [x.type, x.value])); return { y: +p.year, m: +p.month, d: +p.day, wd: p.weekday, h: +p.hour }; };

let week = null;
try { week = JSON.parse(fs.readFileSync(FILE, 'utf8')); } catch (_) { }
let via = 'direct', running = false, lastTry = 0;

async function itimGet(p) {
  if (via === 'direct') {
    try { const t = await get('https://itimlabina.co.il/api/' + p, H, { timeout: 15000 }); return JSON.parse(t); }
    catch (e) { via = 'cf'; log('itim: ישירות לא עובד (' + e.message + ') - דרך Cloudflare'); }
  }
  const t = await get(CF + encodeURIComponent(p), { 'x-key': require('./relay').key() }, { timeout: 20000 });
  return JSON.parse(t);
}

// "6/11/2026" -> רצף הימים; 295 = הדלקת נרות, 310 = צאת השבת/החג (בחג של יומיים - היציאה האחרונה)
function windowsOf(j) {
  const days = Object.keys(j).map(k => { const [d, m, y] = k.split('/').map(Number); return { t: Date.UTC(y, m - 1, d), v: j[k] }; }).sort((a, b) => a.t - b.t);
  const out = []; let cur = null;
  for (const { v } of days) {
    if (v['295']) { if (cur && cur.out) out.push(cur); cur = { in: Date.parse(v['295']), out: 0 }; }
    if (v['310'] && cur) cur.out = Date.parse(v['310']);
  }
  if (cur && cur.out) out.push(cur);
  // השניות נחתכות, כמו בלוח של עתים לבינה (צאת שבת 17:22:40 מוצג 17:22 - לא מעגלים ל-17:23)
  const r = ms => Math.floor(ms / 60000) * 60000;
  return out.map(w => [r(w.in), r(w.out)]);
}

async function fetchWeek() {
  if (running) return; running = true; lastTry = Date.now();
  try {
    const places = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'data', 'shabbat.json'), 'utf8')).c;
    const t = il(Date.now()), from = Date.UTC(t.y, t.m - 1, t.d), to = from + 11 * 86400000;
    const res = {}; let okN = 0; const q = places.map((c, i) => [c, i]);
    via = 'direct';
    await Promise.all([0, 1, 2, 3].map(async () => {
      while (q.length) {
        const [c, i] = q.shift();
        for (let a = 0; a < 3; a++) {
          try {
            const j = await itimGet(`times/bydatesrange/${c.lu}/251/${fmt(from)}/${fmt(to)}/${c.lat}/${c.lng}/${c.el}/null`);
            res[i] = windowsOf(j); okN++; break;
          } catch (e) { if (a === 2) log('itim:', c.n, e.message); }
        }
      }
    }));
    if (okN < places.length * 0.9) throw new Error(`רק ${okN}/${places.length} מקומות`);
    week = { at: Date.now(), from, to, via, places: res, cut: 1 };
    fs.writeFileSync(FILE + '.tmp', JSON.stringify(week)); fs.renameSync(FILE + '.tmp', FILE);
    log(`itim: זמני שבת לשבוע הבא נטענו (${okN} מקומות, ${via === 'cf' ? 'דרך Cloudflare' : 'ישירות'})`);
  } catch (e) { log('itim: טעינה נכשלה -', e.message); }
  finally { running = false; }
}

// כל דקה: במוצאי שבת מ-22:00 (פעם אחת), ובכל מקרה כשאין קובץ / הוא נגמר בעוד פחות מ-4 ימים - ניסיון כל שעה
setInterval(() => {
  const t = il(Date.now());
  const satNight = t.wd === 'Sat' && t.h >= 22 && (!week || Date.now() - week.at > 20 * 3600000);
  const stale = !week || week.to - Date.now() < 4 * 86400000;
  if ((satNight || stale) && Date.now() - lastTry > (satNight && !stale ? 15 * 60000 : 3600000)) fetchWeek();
}, 60 * 1000);
// גם קובץ ישן שנשמר עם עיגול לדקה הקרובה (בלי cut) - נטען מחדש
setTimeout(() => { if (!week || !week.cut || week.to - Date.now() < 4 * 86400000) fetchWeek(); }, 20 * 1000);

/** החלונות מהאתר למקום (אינדקס ב-data/shabbat.json) - או null, וטווח התאריכים שהם מכסים */
function windowsFor(i) {
  if (!week || !week.places || !week.places[i]) return null;
  return { from: week.from, to: week.to, list: week.places[i] };
}
function status() { return week ? { at: week.at, via: week.via, places: Object.keys(week.places || {}).length } : null; }

module.exports = { windowsFor, status, fetchWeek };
