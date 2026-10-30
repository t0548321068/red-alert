// טלגרם: 4 ערוצים ציבוריים (t.me/s), כל 2 שניות. חסימה / שגיאה - הערוץ נח 30 שניות (שלא ייחסם השרת)
const { get, once, fail } = require('../lib/util');
const { send } = require('../lib/push');
const { ok } = require('../lib/health');
const { PRE, END } = require('./tzofar');

const CHANNELS = ['PikudHaOref_all', 'tzevaadomm', 'CumtaAlertsChannel', 'Radar_Alerts'];
const lastPost = {};
const restUntil = {};

const decode = h => h.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')
  .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n)).replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
  .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&');
const SECONDS_RE = /\(\s*[\d\s]*(שניות|דקות|מיידי)[^)]*\)/g, DATE_RE = /\(\d{1,2}\/\d{1,2}\/\d{4}\).*$/, ENDED_IN_RE = /הסתיים ב(.+)/;
const skip = l => l.startsWith('אזור') || /מרחב המוגן|השוהים|הסתיים|בדקות הקרובות|\.|היכנסו|בהמשך ל|נעלו|הישארו|\|/.test(l) ||
  l.startsWith('עדכון') || l.startsWith('על תושבי');

function parse(text) {
  const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
  if (!lines.length) return null;
  let title;
  if (/הסתיים|הוסר החשש|החשש הוסר|ניתן לצאת/.test(text)) title = END;
  else if (text.includes('בדקות הקרובות')) title = PRE;
  else if (text.includes('כלי טיס')) title = 'חדירת כלי טיס עוין';
  else if (text.includes('מחבלים')) title = 'חדירת מחבלים';
  else if (/צבע אדום|רקטות|אזעקה/.test(text)) title = 'ירי רקטות וטילים';
  else title = lines[0].replace(DATE_RE, '').replace(/[^\p{L}\p{N} '"-]/gu, '').trim();
  if (!title) return null;
  const hasBullets = lines.some(l => l.includes('•'));
  let under = false;
  const areas = lines.flatMap((l, i) => {
    const ended = ENDED_IN_RE.exec(l), bullet = l.includes('•');
    const afterBullet = l.split('•').slice(1).join('•');
    const ci = afterBullet.lastIndexOf(':'), afterColon = ci >= 0 ? afterBullet.slice(ci + 1).trim() : '';
    if (ended) return ended[1].split(',');
    if (bullet && l.includes(' - ')) { under = false; return l.slice(l.indexOf(' - ') + 3).split(','); }
    if (bullet && afterColon) { under = false; return afterColon.split(','); }
    if (bullet) { under = true; return []; }
    if (i > 0 && (!hasBullets || under) && !skip(l)) return l.split(',');
    return [];
  }).map(a => a.replace(SECONDS_RE, '').trim().replace(/\.+$/, '')).filter(a => a && a.length <= 40);
  return areas.length ? { title, areas } : null;
}

function poll(ch) {
  const key = 'tg:' + ch;
  if (Date.now() < (restUntil[ch] || 0)) return;
  return once(key, async () => {
    try {
      const html = await get('https://t.me/s/' + ch); ok(key);
      const re = new RegExp('data-post="' + ch + '/(\\d+)"', 'gi');
      const posts = [...html.matchAll(re)];
      const parsed = posts.map((m, i) => {
        const end = i + 1 < posts.length ? posts[i + 1].index : html.length;
        const tm = /js-message_text[^>]*>([\s\S]*?)<\/div>/.exec(html.slice(m.index, end));
        return tm ? [+m[1], tm[1]] : null;
      }).filter(Boolean);
      if (!parsed.length) return;
      const newest = Math.max(...parsed.map(p => p[0]));
      if (lastPost[ch] === undefined) { lastPost[ch] = newest; return; }   // טעינה ראשונה: בלי הודעות ישנות
      for (const [, raw] of parsed.filter(p => p[0] > lastPost[ch]).sort((a, b) => a[0] - b[0])) {
        const r = parse(decode(raw));
        if (r) send(r.title, r.areas, key);
      }
      lastPost[ch] = Math.max(lastPost[ch], newest);
    } catch (e) {
      fail(key, e);
      restUntil[ch] = Date.now() + 30 * 1000;
    }
  });
}

function start() { setInterval(() => CHANNELS.forEach(poll), 2000); }

module.exports = { start };
