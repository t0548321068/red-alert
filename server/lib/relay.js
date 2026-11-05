// הממסר בבית: טלפון ישן עם האפליקציה "ממסר צופר" בודק את פיקוד העורף מהאינטרנט הביתי
// (פיקוד העורף חוסם שרתי ענן מנובמבר 2026) ומעביר לכאן את התשובות כמו שהן. השרת מעבד אותן כאילו בדק בעצמו.
// רק מי שיש לו מפתח הממסר (מופיע באתר) יכול לשלוח - שאף אחד לא ישלח התרעות מזויפות לכל הטלפונים.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { log } = require('./util');
const { ok } = require('./health');

const KEY_FILE = path.join(__dirname, '..', 'relay.key');   // לא נכנס לגיט
let key = '';
try { key = fs.readFileSync(KEY_FILE, 'utf8').trim(); } catch (_) {}
if (!key) {
  // 12 תווים בלי אותיות דומות (0/O, 1/I) - נוח להקליד בטלפון
  const A = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  key = [...crypto.randomBytes(12)].map(b => A[b % A.length]).join('').replace(/(.{4})(?=.)/g, '$1-');
  fs.writeFileSync(KEY_FILE, key + '\n', { mode: 0o600 });
}
const norm = s => String(s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
const keyOk = k => { const a = Buffer.from(norm(k)), b = Buffer.from(norm(key)); return a.length === b.length && crypto.timingSafeEqual(a, b); };

const seen = { live: 0, history: 0, archive: 0, ping: 0, fcm: 0 };
let forwarded = 0, firstSeen = 0, lastLogged = false;

function body(req, max, done) {
  const bufs = []; let n = 0;
  req.on('data', d => { n += d.length; if (n > max) req.destroy(); else bufs.push(d); });
  req.on('end', () => done(Buffer.concat(bufs).toString('utf8').replace(/^﻿/, '').replace(/\u0000/g, '').trim()));
}

/** /relay/... - מהאפליקציה "ממסר צופר" */
function handle(req, res, u) {
  const kind = u.pathname.slice(7);
  if (!(kind in seen) || req.method !== 'POST') { res.writeHead(404); return res.end(); }
  if (!keyOk(req.headers['x-relay-key'])) { res.writeHead(401); return res.end(); }
  body(req, 3 * 1024 * 1024, t => {
    const now = Date.now();
    if (kind !== 'fcm' && (!lastLogged || now - Math.max(seen.live, seen.ping) > 10 * 60 * 1000)) { log('relay: הממסר בבית מחובר'); firstSeen = now; lastLogged = true; }
    seen[kind] = now;
    // השרת המשני (Cloudflare) והממסר שומרים אצלם את מפתח Firebase - כדי לשלוח לטלפונים כשהשרת הזה לא עונה
    if (kind === 'fcm') {
      let sa = '';
      try { sa = fs.readFileSync(path.join(__dirname, '..', 'service-account.json'), 'utf8'); } catch (_) {}
      res.writeHead(sa ? 200 : 404, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      return res.end(sa);
    }
    let sent = 0;
    const oref = require('../sources/oref');
    try {
      if (kind === 'live') {
        if (!t) ok('oref', 'relay');
        else { JSON.parse(t); ok('oref', 'relay'); if (oref.liveText(t)) sent = 1; }
      } else if (kind === 'history' || kind === 'archive') sent = oref.historyText(kind, t, 'relay') || 0;
    } catch (_) { /* תשובה לא תקינה */ }
    forwarded += sent;
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, sent, now }));
  });
}

/** לאתר: המפתח ומצב הממסר */
function status() { return { key, seen, forwarded, since: firstSeen, now: Date.now() }; }

module.exports = { handle, status, key: () => key };
