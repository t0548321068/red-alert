// טלגרם: 4 ערוצים ציבוריים (t.me/s), כל 2 שניות. חסימה / שגיאה - הערוץ נח 30 שניות (שלא ייחסם השרת)
const { get, once, fail } = require('../lib/util');
const { send } = require('../lib/push');
const { ok } = require('../lib/health');
const P = require('../lib/parse');   // הפענוח עצמו (נבדק בבדיקות)

const CHANNELS = ['PikudHaOref_all', 'tzevaadomm', 'CumtaAlertsChannel', 'Radar_Alerts'];
const lastPost = {};
const restUntil = {};

const parse = P.telegram, decode = P.decodeHtml;

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
