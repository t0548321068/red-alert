// מפת הרחובות מהשרת בירושלים: הטלפונים (והאתר) מקבלים את האריחים, הגופנים ותיאור המפה מכאן,
// והשרת מביא אותם מ-OpenFreeMap פעם אחת ושומר בדיסק. כך הם מגיעים משרת קרוב ומהר.
// /tiles/planet        - תיאור המפה (הכתובות בו מוחלפות לשרת הזה), מתרענן כל שעה
// /tiles/planet/<גרסה>/z/x/y.pbf - אריח (לא משתנה בתוך גרסה)
// /tiles/fonts/<גופן>/<טווח>.pbf - גופן לשמות
// בנוסף: פעם בשבוע (ובהפעלה, אם לא נעשה) - כל הארץ עד זום 12 והגופנים לעברית ולערבית מראש
const fs = require('fs');
const path = require('path');
const https = require('https');
const zlib = require('zlib');
const { log } = require('./util');

const UP = 'https://tiles.openfreemap.org/';
const DIR = path.join(__dirname, '..', 'tilecache');
const agent = new https.Agent({ keepAlive: true, maxSockets: 6 });
const inflight = new Map();
const JSON_TTL = 3600 * 1000, KEEP_DAYS = 45;

function file(p) { return path.join(DIR, p.replace(/[^\w\/.%-]/g, '_')); }
function isJson(p) { return !p.endsWith('.pbf') && !p.endsWith('.png'); }

function fetchUp(p) {
  if (inflight.has(p)) return inflight.get(p);
  const pr = new Promise((resolve, reject) => {
    const req = https.get(UP + p, { agent, headers: { 'Accept-Encoding': 'gzip', 'User-Agent': 'tzofar-server' }, timeout: 15000 }, r => {
      const parts = []; r.on('data', d => parts.push(d));
      r.on('end', () => {
        let b = Buffer.concat(parts);
        if (r.statusCode === 204) return resolve(Buffer.alloc(0));
        if (r.statusCode !== 200) return reject(new Error('HTTP ' + r.statusCode));
        try { if (r.headers['content-encoding'] === 'gzip') b = zlib.gunzipSync(b); } catch (e) { return reject(e); }
        resolve(b);
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  }).finally(() => inflight.delete(p));
  inflight.set(p, pr);
  return pr;
}

/** מהדיסק, או מ-OpenFreeMap (ונשמר). JSON - מתרענן כל שעה; אם אין רשת - הישן */
async function get(p) {
  const f = file(p);
  let st = null; try { st = fs.statSync(f); } catch (_) { }
  if (st && (!isJson(p) || Date.now() - st.mtimeMs < JSON_TTL)) return fs.readFileSync(f);
  try {
    const b = await fetchUp(p);
    fs.mkdirSync(path.dirname(f), { recursive: true });
    fs.writeFileSync(f + '.tmp', b); fs.renameSync(f + '.tmp', f);
    return b;
  } catch (e) { if (st) return fs.readFileSync(f); throw e; }
}

function base(req) {
  const proto = String(req.headers['x-forwarded-proto'] || 'http').split(',')[0].trim();
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(',')[0].trim();
  return `${proto}://${host}/tiles/`;
}

async function serve(req, res, p) {
  if (!/^(planet(\/[\w.-]+\/\d+\/\d+\/\d+\.pbf)?|fonts\/[\w %.,-]+\/\d+-\d+\.pbf)$/.test(p)) { res.writeHead(404); return res.end(); }
  try {
    let b = await get(p);
    const h = { 'Access-Control-Allow-Origin': '*' };
    if (isJson(p)) {
      b = Buffer.from(b.toString('utf8').split(UP).join(base(req)));
      Object.assign(h, { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=3600' });
    } else Object.assign(h, { 'Content-Type': 'application/x-protobuf', 'Cache-Control': 'public, max-age=2592000, immutable' });
    // דחוס כשאפשר - פחות להוריד
    if (b.length > 512 && /\bgzip\b/.test(String(req.headers['accept-encoding'] || ''))) { b = zlib.gzipSync(b, { level: 6 }); h['Content-Encoding'] = 'gzip'; }
    h['Content-Length'] = b.length;
    res.writeHead(200, h); res.end(b);
  } catch (e) { res.writeHead(502); res.end(); }
}

// ---- מראש: כל הארץ עד זום 12, וגופנים ----
const BOX = { s: 29.45, n: 33.35, w: 34.2, e: 35.95 };
const FONTS = ['Noto Sans Regular', 'Noto Sans Bold'], RANGES = ['0-255', '256-511', '1280-1535', '1536-1791', '8192-8447'];
const tx = (lon, z) => Math.floor((lon + 180) / 360 * 2 ** z);
const ty = (lat, z) => { const r = lat * Math.PI / 180; return Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * 2 ** z); };
const MARK = path.join(DIR, 'warm.json');
let warming = false;
async function warm() {
  if (warming) return; warming = true;
  try {
    const tj = JSON.parse((await get('planet')).toString('utf8'));
    const tpl = String((tj.tiles || [])[0] || '');
    const m = tpl.match(/planet\/([\w.-]+)\/\{z\}/);
    if (!m) throw new Error('אין תבנית אריחים');
    const ver = m[1];
    const list = [];
    for (const f of FONTS) for (const r of RANGES) list.push(`fonts/${f}/${r}.pbf`);
    for (let z = 5; z <= 12; z++)
      for (let x = tx(BOX.w, z); x <= tx(BOX.e, z); x++)
        for (let y = ty(BOX.n, z); y <= ty(BOX.s, z); y++) list.push(`planet/${ver}/${z}/${x}/${y}.pbf`);
    let ok = 0, i = 0;
    await Promise.all([0, 1, 2].map(async () => {
      while (i < list.length) { const p = list[i++]; try { await get(p); ok++; } catch (_) { } }
    }));
    fs.writeFileSync(MARK, JSON.stringify({ at: Date.now(), ver, ok, all: list.length }));
    log(`tiles: הארץ מוכנה מראש (${ok}/${list.length}, גרסה ${ver})`);
  } catch (e) { log('tiles: הכנה מראש נכשלה -', e.message); }
  finally { warming = false; }
}
function lastWarm() { try { return JSON.parse(fs.readFileSync(MARK, 'utf8')); } catch (_) { return null; } }
// ניקוי: אריחים של גרסאות ישנות (לא נגעו בהם 45 יום)
function prune() {
  const old = Date.now() - KEEP_DAYS * 86400000;
  const walk = d => { let n = 0; try { for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const f = path.join(d, e.name);
    if (e.isDirectory()) { n += walk(f); try { fs.rmdirSync(f); } catch (_) { } }
    else if (e.name !== 'warm.json') { try { if (fs.statSync(f).mtimeMs < old) { fs.unlinkSync(f); n++; } } catch (_) { } }
  } } catch (_) { } return n; };
  const n = walk(path.join(DIR, 'planet')); if (n) log(`tiles: נמחקו ${n} אריחים ישנים`);
}
setTimeout(() => { const w = lastWarm(); if (!w || Date.now() - w.at > 7 * 86400000) warm(); }, 60 * 1000);
setInterval(() => { const w = lastWarm(); if (!w || Date.now() - w.at > 7 * 86400000) warm(); prune(); }, 6 * 3600 * 1000);

function status() { const w = lastWarm(); return w ? { at: w.at, ver: w.ver, ok: w.ok, all: w.all } : null; }
module.exports = { serve, status, warm };
