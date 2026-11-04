// Cloudflare Worker: מביא את קבצי פיקוד העורף בשביל השרת (פיקוד העורף חוסם את Oracle מנובמבר 2026).
// רק שלוש הכתובות הקבועות, ורק עם המפתח (משתנה KEY ב-Cloudflare = מפתח הממסר מהאתר) - לא פרוקסי פתוח.
const OREF = {
  live: 'https://www.oref.org.il/warningMessages/alert/Alerts.json',
  history: 'https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json',
  archive: 'https://www.oref.org.il/api/v1/AlertHistory/heb?period=lastDay',
  home: 'https://www.oref.org.il/',
};
const norm = s => String(s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');

export default {
  async fetch(req, env) {
    const kind = new URL(req.url).pathname.slice(1);
    if (!OREF[kind]) return new Response('not found', { status: 404 });
    if (!env.KEY || norm(req.headers.get('x-key')) !== norm(env.KEY)) return new Response('unauthorized', { status: 401 });
    const url = OREF[kind] + (OREF[kind].includes('?') ? '&' : '?') + 't=' + Date.now();
    const r = await fetch(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
        Accept: 'application/json, text/plain, */*',
        'Accept-Language': 'he-IL,he;q=0.9,en;q=0.8',
        Referer: 'https://www.oref.org.il/',
        'X-Requested-With': 'XMLHttpRequest',
      },
      cf: { cacheTtl: 0, cacheEverything: false },
    });
    // התשובה כמו שהיא (גם UTF-16), עם הקוד של פיקוד העורף ומאיזה מרכז נתונים של Cloudflare יצאה הבקשה
    return new Response(await r.arrayBuffer(), {
      status: r.status,
      headers: { 'Content-Type': r.headers.get('content-type') || 'application/octet-stream', 'X-Colo': (req.cf && req.cf.colo) || '', 'Cache-Control': 'no-store' },
    });
  },
};
