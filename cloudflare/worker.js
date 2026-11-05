// השרת המשני (Cloudflare Worker "oref-proxy"). שני תפקידים:
// 1. מעבר לפיקוד העורף בשביל השרת הראשי (/live /history /archive /home) - פיקוד העורף חוסם את Oracle מנובמבר 2026.
// 2. גיבוי מלא: פעם בדקה בודק שהשרת הראשי חי. אם לא ענה פעמיים ברצף - בודק את המקורות בעצמו ושולח לטלפונים
//    התרעות וסימני חיים דרך Firebase (כך הטלפונים לא עוברים לבדוק בעצמם ולא צורכים סוללה). כשהראשי חוזר - חוזר להמתנה.
// בנוי לפי המגבלות של Cloudflare בחינם (100 אלף בקשות ביום, 10ms עיבוד לבקשה, 50 בקשות משנה, 100 אלף כתיבות ביום):
// בהמתנה - בקשה אחת בדקה. בגיבוי - בדיקה כל 5 שניות, מקור טלגרם אחד בכל פעם, שמירה לאחסון רק כשיש התרעה.
const PRIMARY = 'https://tzofar-app.duckdns.org';
const OREF = {
  live: 'https://www.oref.org.il/warningMessages/alert/Alerts.json',
  history: 'https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json',
  archive: 'https://www.oref.org.il/api/v1/AlertHistory/heb?period=lastDay',
  home: 'https://www.oref.org.il/',
};
const OREF_H = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
  Accept: 'application/json, text/plain, */*', 'Accept-Language': 'he-IL,he;q=0.9,en;q=0.8',
  Referer: 'https://www.oref.org.il/', 'X-Requested-With': 'XMLHttpRequest',
};
const norm = s => String(s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
const keyOk = (req, env) => env.KEY && norm(req.headers.get('x-key')) === norm(env.KEY);

export default {
  async fetch(req, env) {
    const kind = new URL(req.url).pathname.slice(1);
    // מצב הגיבוי (בלי מפתח - רק כן/לא, בשביל הממסר בבית)
    if (kind === 'health') {
      const s = await env.BACKUP.get(env.BACKUP.idFromName('main')).fetch('https://do/state');
      const j = await s.json();
      return Response.json({ ok: true, active: j.active, primaryOk: j.primaryOk });
    }
    if (kind === 'state') {
      if (!keyOk(req, env)) return new Response('unauthorized', { status: 401 });
      return env.BACKUP.get(env.BACKUP.idFromName('main')).fetch('https://do/state');
    }
    if (!OREF[kind]) return new Response('not found', { status: 404 });
    if (!keyOk(req, env)) return new Response('unauthorized', { status: 401 });
    const r = await fetch(OREF[kind] + (OREF[kind].includes('?') ? '&' : '?') + 't=' + Date.now(), { headers: OREF_H });
    return new Response(await r.arrayBuffer(), {
      status: r.status,
      headers: { 'Content-Type': r.headers.get('content-type') || 'application/octet-stream', 'X-Colo': (req.cf && req.cf.colo) || '', 'Cache-Control': 'no-store' },
    });
  },
  // פעם בדקה
  async scheduled(ev, env, ctx) {
    ctx.waitUntil(env.BACKUP.get(env.BACKUP.idFromName('main')).fetch('https://do/tick'));
  },
};

// ---- מקורות ----
const THREATS = { 0: 'ירי רקטות וטילים', 1: 'אירוע חומרים מסוכנים', 2: 'חדירת מחבלים', 3: 'רעידת אדמה', 4: 'חשש לצונאמי',
  5: 'חדירת כלי טיס עוין', 6: 'חשש לאירוע רדיולוגי', 7: 'חשש לאירוע כימי', 8: 'התרעות פיקוד העורף' };
const PRE = 'בדקות הקרובות צפויות להתקבל התרעות באזורך', END = 'האירוע הסתיים';
const CHANNELS = ['PikudHaOref_all', 'tzevaadomm', 'CumtaAlertsChannel', 'Radar_Alerts'];
const TICK_MS = 5000;

function ilTime(s) {
  // "2026-11-04 21:30:15" שעון ישראל -> מילישניות (חורף +2, קיץ +3: בודקים את שתיהן לפי הפרש מהשעה הנוכחית)
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/.exec(String(s || ''));
  if (!m) return 0;
  const utc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  const a = utc - 2 * 3600e3, b = utc - 3 * 3600e3, now = Date.now();
  return Math.abs(now - a) < Math.abs(now - b) ? a : b;
}
const decode = h => h.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')
  .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n)).replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'")
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&');
const SECONDS_RE = /\(\s*[\d\s]*(שניות|דקות|מיידי)[^)]*\)/g, ENDED_IN_RE = /הסתיים ב(.+)/;
const skip = l => l.startsWith('אזור') || /מרחב המוגן|השוהים|הסתיים|בדקות הקרובות|\.|היכנסו|בהמשך ל|נעלו|הישארו|\|/.test(l) || l.startsWith('עדכון') || l.startsWith('על תושבי');
// הודעת טלגרם -> סוג + יישובים (גרסה מקוצרת של השרת הראשי)
function parseTg(text) {
  const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
  if (!lines.length) return null;
  let title;
  if (/הסתיים|הוסר החשש|החשש הוסר|ניתן לצאת/.test(text)) title = END;
  else if (text.includes('בדקות הקרובות')) title = PRE;
  else if (text.includes('כלי טיס')) title = 'חדירת כלי טיס עוין';
  else if (text.includes('מחבלים')) title = 'חדירת מחבלים';
  else if (/צבע אדום|רקטות|אזעקה/.test(text)) title = 'ירי רקטות וטילים';
  else return null;
  const hasBullets = lines.some(l => l.includes('•'));
  let under = false;
  const areas = lines.flatMap((l, i) => {
    const ended = ENDED_IN_RE.exec(l), bullet = l.includes('•');
    const after = l.split('•').slice(1).join('•'), ci = after.lastIndexOf(':'), afterColon = ci >= 0 ? after.slice(ci + 1).trim() : '';
    if (ended) return ended[1].split(',');
    if (bullet && l.includes(' - ')) { under = false; return l.slice(l.indexOf(' - ') + 3).split(','); }
    if (bullet && afterColon) { under = false; return afterColon.split(','); }
    if (bullet) { under = true; return []; }
    if (i > 0 && (!hasBullets || under) && !skip(l)) return l.split(',');
    return [];
  }).map(a => a.replace(SECONDS_RE, '').trim().replace(/\.+$/, '')).filter(a => a && a.length <= 40);
  return areas.length ? { title, areas } : null;
}

// ---- Firebase: אסימון גישה ממפתח השירות (JWT חתום RS256), ושליחה לנושא ----
const b64u = buf => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const b64uStr = s => b64u(new TextEncoder().encode(s));
async function accessToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64uStr(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const body = b64uStr(JSON.stringify({ iss: sa.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  const der = Uint8Array.from(atob(pem), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(head + '.' + body));
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=' + head + '.' + body + '.' + b64u(sig),
  });
  const j = await r.json();
  if (!j.access_token) throw new Error('token ' + r.status);
  return { token: j.access_token, exp: Date.now() + (j.expires_in - 300) * 1000 };
}

export class Backup {
  constructor(state, env) {
    this.state = state; this.env = env;
    this.mem = null;   // מצב בזיכרון (נטען מהאחסון בפעם הראשונה)
  }
  async load() {
    if (this.mem) return this.mem;
    const s = (await this.state.storage.get('s')) || {};
    this.mem = { active: false, fails: 0, primaryOk: 0, sa: null, saAt: 0, tok: null, orefId: '', tzofar: '', tg: {}, sent: {}, okAt: {}, n: 0, hbAt: 0, since: 0, ...s };
    return this.mem;
  }
  // שמירה לאחסון: רק כשמשהו חשוב השתנה (מגבלת כתיבות)
  async save() { const { tok, ...rest } = this.mem; await this.state.storage.put('s', rest); }

  async fetch(req) {
    const m = await this.load();
    const p = new URL(req.url).pathname;
    if (p === '/state') return Response.json({ active: m.active, primaryOk: m.primaryOk, since: m.since, fails: m.fails, okAt: m.okAt, hasKey: !!m.sa });
    if (p === '/tick') { await this.tick(); return new Response('ok'); }
    return new Response('not found', { status: 404 });
  }

  // פעם בדקה: האם השרת הראשי חי
  async tick() {
    const m = this.mem;
    let up = false;
    try {
      // /alive - בלי לגרום לשרת הראשי לשלוח סימן חיים לטלפונים (כמו /status)
      const r = await fetch(PRIMARY + '/alive', { signal: AbortSignal.timeout(8000) });
      up = r.ok;
    } catch (_) {}
    if (up) {
      m.primaryOk = Date.now();
      const wasActive = m.active, wasFail = m.fails > 0;
      m.fails = 0;
      // מפתח Firebase מהשרת הראשי (פעם ביום) - שיהיה מוכן כשיצטרכו אותו
      if (!m.sa || Date.now() - m.saAt > 24 * 3600e3) {
        try {
          const r = await fetch(PRIMARY + '/relay/fcm', { method: 'POST', headers: { 'X-Relay-Key': this.env.KEY } });
          if (r.ok) { m.sa = await r.json(); m.saAt = Date.now(); await this.save(); }
        } catch (_) {}
      }
      if (wasActive) {
        m.active = false; await this.state.storage.deleteAlarm(); await this.save();
        await this.admin('✅ השרת הראשי חזר לעבוד – השרת המשני חזר להמתנה');
      } else if (wasFail) await this.save();
      return;
    }
    m.fails++;
    if (!m.active && m.fails >= 2) {
      m.active = true; m.since = Date.now(); m.n = 0; m.hbAt = 0;
      await this.save();
      await this.state.storage.setAlarm(Date.now() + 1000);
      await this.admin('⚠️ השרת הראשי לא עונה – השרת המשני (Cloudflare) נכנס לפעולה');
    } else if (!m.active) await this.save();
  }

  // מצב גיבוי: כל 5 שניות
  async alarm() {
    const m = await this.load();
    if (!m.active) return;
    await this.state.storage.setAlarm(Date.now() + TICK_MS);
    m.n++;
    const jobs = [this.orefLive(), this.tzofar(), this.telegram(CHANNELS[m.n % CHANNELS.length])];
    if (m.n % 12 === 1) jobs.push(this.orefHistory());   // כל דקה
    await Promise.allSettled(jobs);
    // סימן חיים לטלפונים כל 10 דקות (כמו השרת הראשי) - שלא יעברו לבדוק בעצמם
    if (Date.now() - m.hbAt > 10 * 60e3) { m.hbAt = Date.now(); await this.heartbeat(); }
  }

  ok(k) { this.mem.okAt[k] = Date.now(); }

  async orefLive() {
    const r = await fetch(OREF.live + '?t=' + Date.now(), { headers: OREF_H });
    if (!r.ok) return;
    let t = await r.text(); t = t.replace(/^﻿/, '').replace(/\u0000/g, '').trim();
    this.ok('oref');
    if (!t) return;
    const j = JSON.parse(t);
    if (!j.id || j.id === this.mem.orefId) return;
    this.mem.orefId = j.id;
    if (Array.isArray(j.data) && j.data.length) await this.send(j.title || 'התראה', j.data, 'oref', Date.now(), String(j.id));
  }
  async orefHistory() {
    const r = await fetch(OREF.history + '?t=' + Date.now(), { headers: OREF_H });
    if (!r.ok) return;
    const t = (await r.text()).replace(/^﻿/, '').trim();
    this.ok('history');
    if (!t.startsWith('[')) return;
    const by = new Map();
    for (const o of JSON.parse(t)) {
      const at = ilTime(o.alertDate);
      if (!at || Date.now() - at > 90e3) continue;
      const k = (o.title || 'התראה') + '|' + at;
      if (!by.has(k)) by.set(k, { title: o.title || 'התראה', at, areas: [] });
      by.get(k).areas.push(o.data);
    }
    for (const e of by.values()) await this.send(e.title, e.areas, 'history', e.at);
  }
  async tzofar() {
    const r = await fetch('https://api.tzevaadom.co.il/notifications', { headers: { Origin: 'https://www.tzevaadom.co.il' } });
    if (!r.ok) return;
    const a = await r.json();
    this.ok('tzofar');
    for (const d of Array.isArray(a) ? a : []) {
      const id = String(d.notificationId || d.time);
      if (d.isDrill || !Array.isArray(d.cities) || !THREATS[d.threat ?? 0] || this.mem.tzofar.includes('|' + id + '|')) continue;
      this.mem.tzofar = ('|' + id + '|' + this.mem.tzofar).slice(0, 600);
      await this.send(THREATS[d.threat ?? 0], d.cities, 'tzofar', d.time ? d.time * 1000 : Date.now());
    }
  }
  async telegram(ch) {
    const key = 'tg:' + ch;
    const r = await fetch('https://t.me/s/' + ch);
    if (!r.ok) return;
    const html = await r.text();
    this.ok(key);
    // רק סוף העמוד (ההודעות האחרונות) - חוסך עיבוד
    const tail = html.slice(-60000);
    const re = new RegExp('data-post="' + ch + '/(\\d+)"', 'gi');
    const posts = [...tail.matchAll(re)];
    if (!posts.length) return;
    const newest = Math.max(...posts.map(p => +p[1]));
    const last = this.mem.tg[ch];
    this.mem.tg[ch] = newest;
    if (last === undefined) return;   // פעם ראשונה: בלי הודעות ישנות
    for (let i = 0; i < posts.length; i++) {
      if (+posts[i][1] <= last) continue;
      const end = i + 1 < posts.length ? posts[i + 1].index : tail.length;
      const tm = /js-message_text[^>]*>([\s\S]*?)<\/div>/.exec(tail.slice(posts[i].index, end));
      const p = tm && parseTg(decode(tm[1]));
      if (p) await this.send(p.title, p.areas, key);
    }
  }

  // שליחה לטלפונים - אותה הודעה כמו מהשרת הראשי. אותו יישוב + סוג ב-2 דקות - פעם אחת
  async send(title, areas, src, at = Date.now(), eid = '') {
    const m = this.mem, now = Date.now();
    for (const k in m.sent) if (now - m.sent[k] > 120e3) delete m.sent[k];
    areas = [...new Set(areas.map(a => String(a).trim()).filter(Boolean))].filter(a => !m.sent[title + '|' + a]);
    if (!areas.length) return;
    for (const a of areas) m.sent[title + '|' + a] = now;
    const chunks = []; let cur = [];
    for (const a of areas) { if (JSON.stringify([...cur, a]).length > 1500) { chunks.push(cur); cur = []; } cur.push(a); }
    if (cur.length) chunks.push(cur);
    for (const part of chunks)
      await this.fcm({ topic: 'alerts', data: { type: 'alert', title, areas: part.join('|'), src, at: String(at), eid: String(eid) }, android: { priority: 'HIGH', ttl: '60s' } });
    await this.save();
  }
  async heartbeat() {
    const up = Object.keys(this.mem.okAt).filter(k => Date.now() - this.mem.okAt[k] < 5 * 60e3);
    await this.fcm({ topic: 'alerts', data: { type: 'hb', up: up.join(','), at: String(Date.now()), dv: '', cn: '0', by: 'secondary', sec: 'active', rel: '' }, android: { priority: 'HIGH', ttl: '300s' } });
  }
  async admin(text) {
    await this.fcm({ topic: 'admin', data: { type: 'admin', text }, android: { priority: 'HIGH', ttl: '3600s' } });
  }
  async fcm(message) {
    const m = this.mem;
    if (!m.sa) return false;
    try {
      if (!m.tok || Date.now() > m.tok.exp) m.tok = await accessToken(m.sa);
      const r = await fetch('https://fcm.googleapis.com/v1/projects/' + m.sa.project_id + '/messages:send', {
        method: 'POST', headers: { Authorization: 'Bearer ' + m.tok.token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ message }),
      });
      return r.ok;
    } catch (_) { return false; }
  }
}
