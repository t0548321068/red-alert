// זמני שבת וחג: אותו חישוב כמו באפליקציה (עתים לבינה: 162 יישובים עם מנהג המקום), במקום אחד.
// כך תיקון זמנים נעשה בשרת, בלי לעדכן את האפליקציה. מחזיר את השבתות והחגים של החודשיים הקרובים
const fs = require('fs');
const path = require('path');

const SH_CITIES = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'shabbat_cities.json'), 'utf8'));
function nearest(lat, lon) {
  const k = Math.cos(lat * Math.PI / 180); let best = SH_CITIES[0], bd = Infinity;
  for (const c of SH_CITIES) { const dy = c[0] - lat, dx = (c[1] - lon) * k, d = dy * dy + dx * dx; if (d < bd) { bd = d; best = c; } }
  return best;
}
const ilParts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Jerusalem', year: 'numeric', month: 'numeric', day: 'numeric', weekday: 'short' });
const hebParts = new Intl.DateTimeFormat('en-u-ca-hebrew', { timeZone: 'Asia/Jerusalem', month: 'long', day: 'numeric' });
function ilDay(ms) { const p = Object.fromEntries(ilParts.formatToParts(new Date(ms)).map(x => [x.type, x.value])); return { y: +p.year, m: +p.month, d: +p.day, wd: p.weekday }; }
function isHoly(ms) {   // ms = צהרי היום
  if (ilDay(ms).wd === 'Sat') return true;
  const p = Object.fromEntries(hebParts.formatToParts(new Date(ms)).map(x => [x.type, x.value]));
  const m = String(p.month || ''), d = +p.day;
  if (/^Tishri/.test(m)) return [1, 2, 10, 15, 22].includes(d);
  if (/^Nisan/.test(m)) return d === 15 || d === 21;
  if (/^Sivan/.test(m)) return d === 6;
  return false;
}
function sunsetUtc(y, mo, d, lat, lon, zenith) {   // NOAA - כמו באפליקציה
  const R = Math.PI / 180;
  const n = Math.round((Date.UTC(y, mo - 1, d) - Date.UTC(y, 0, 0)) / 86400000);
  const lngHour = lon / 15, t = n + ((18 - lngHour) / 24), m = 0.9856 * t - 3.289;
  let l = m + 1.916 * Math.sin(m * R) + 0.020 * Math.sin(2 * m * R) + 282.634; l = (l + 360) % 360;
  let ra = Math.atan(0.91764 * Math.tan(l * R)) / R; ra = (ra + 360) % 360;
  ra += Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90; ra /= 15;
  const sinDec = 0.39782 * Math.sin(l * R), cosDec = Math.cos(Math.asin(sinDec));
  const cosH = (Math.cos(zenith * R) - sinDec * Math.sin(lat * R)) / (cosDec * Math.cos(lat * R));
  const h = Math.acos(Math.max(-1, Math.min(1, cosH))) / R / 15;
  const ut = (((h + ra - 0.06571 * t - 6.622) - lngHour) % 24 + 24) % 24;
  return Date.UTC(y, mo - 1, d) + ut * 3600000;
}
const roundMin = ms => Math.floor((ms + 30000) / 60000) * 60000;
function windows(lat, lon) {
  const c = nearest(lat, lon), out = [], DAY = 86400000;
  const noon = t => { const x = ilDay(t); return Date.UTC(x.y, x.m - 1, x.d, 10); };   // ~12:00 בישראל
  let day = noon(Date.now() - DAY);
  for (let i = 0; i < 62 && out.length < 12; i++, day += DAY) {
    if (!isHoly(day) || isHoly(day - DAY)) continue;
    const p = ilDay(day - DAY);
    const start = roundMin(sunsetUtc(p.y, p.m, p.d, lat, lon, 90.833) + c[2] * 60000);
    let last = day; while (isHoly(last + DAY)) last += DAY;
    const q = ilDay(last);
    const end = roundMin(sunsetUtc(q.y, q.m, q.d, lat, lon, 98.5) + c[3] * 60000);
    if (end > Date.now()) out.push({ in: start, out: end });
  }
  return out;
}
function serve(res, lat, lon) {
  if (!isFinite(lat) || !isFinite(lon)) { res.writeHead(400); return res.end(); }
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ windows: windows(lat, lon), at: Date.now() }));
}

module.exports = { serve };
