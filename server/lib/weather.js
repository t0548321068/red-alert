// מזג אוויר מ-OpenWeather: המפתח רק כאן בשרת (הקובץ openweather.key, לא נכנס לגיט).
// הקוד מתורגם לקודי WMO שהאפליקציה מכירה. שמור 5 דקות לכל ~100 מטר, עם שם המקום מ-OpenStreetMap
const fs = require('fs');
const path = require('path');
const { get, log } = require('./util');

const wxCache = new Map();
function wmo(id) {
  if (id >= 200 && id < 300) return 95;
  if (id >= 300 && id < 400) return 51;
  if (id === 511) return 66;
  if (id >= 520 && id < 600) return 80;
  if (id >= 500 && id < 600) return 61;
  if (id >= 600 && id < 700) return 71;
  if (id >= 700 && id < 800) return 45;
  if (id === 800) return 0;
  if (id === 801) return 1;
  if (id === 802) return 2;
  return 3;
}

// מכסה: עד 40 פניות בדקה ל-OpenWeather (החינמי - 60). מעבר לזה הטלפון עובר ל-Open-Meteo
const owm = { start: 0, n: 0 };
async function serve(res, lat, lon) {
  const fail = c => { res.writeHead(c); res.end(); };
  let key = '';
  try { key = fs.readFileSync(path.join(__dirname, '..', 'openweather.key'), 'utf8').trim(); } catch (_) {}
  if (!key || !isFinite(lat) || !isFinite(lon)) return fail(503);
  const ck = lat.toFixed(3) + ',' + lon.toFixed(3);   // ~100 מטר - שם השכונה נשאר מדויק
  const hit = wxCache.get(ck);
  if (hit && Date.now() - hit.at < 5 * 60 * 1000) { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(hit.body); }
  const now = Date.now();
  if (now - owm.start > 60 * 1000) { owm.start = now; owm.n = 0; }
  if (++owm.n > 40) return fail(503);
  try {
    const j = JSON.parse(await get(`https://api.openweathermap.org/data/2.5/weather?lat=${lat}&lon=${lon}&units=metric&lang=he&appid=${encodeURIComponent(key)}`));
    const w = (j.weather || [])[0] || {};
    const place = await placeName(lat, lon);
    const body = JSON.stringify({ temp: Math.round(j.main.temp), code: wmo(w.id || 800), isDay: String(w.icon || 'd').endsWith('d'), desc: w.description || '', place });
    wxCache.set(ck, { at: Date.now(), body });
    if (wxCache.size > 500) wxCache.delete(wxCache.keys().next().value);
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(body);
  } catch (e) { log('weather failed', e.message); fail(502); }
}

// ---- שם המקום ("שכונה, עיר") ----
const placeCache = new Map();
let nomNext = 0;
async function placeName(lat, lon) {
  const k = lat.toFixed(3) + ',' + lon.toFixed(3);
  if (placeCache.has(k)) return placeCache.get(k);
  // OpenStreetMap: לכל היותר פנייה אחת בשנייה (הכלל שלהם). תור ארוך מדי - בלי שם הפעם
  const now = Date.now(), wait = Math.max(0, nomNext - now);
  if (wait > 5000) return '';
  nomNext = Math.max(now, nomNext) + 1000;
  if (wait) await new Promise(r => setTimeout(r, wait));
  let name = '';
  try {
    const a = (JSON.parse(await get(`https://nominatim.openstreetmap.org/reverse?format=json&zoom=18&lat=${lat}&lon=${lon}&accept-language=he`,
      { 'User-Agent': 'TzofarHerumServer/1.0 (t0548321068 red-alert)' })).address) || {};
    const city = ['city', 'town', 'village', 'municipality'].map(x => a[x]).find(x => x && !x.startsWith('מועצה אזורית'));   // מועצה אזורית = השטח שמסביב, לא המקום
    const hood = ['neighbourhood', 'suburb', 'quarter', 'residential'].map(x => a[x]).find(x => x && x !== city);
    name = [hood, city].filter(Boolean).join(', ');
  } catch (_) {}
  if (name) { placeCache.set(k, name); if (placeCache.size > 5000) placeCache.delete(placeCache.keys().next().value); }
  return name;
}

module.exports = { serve };
