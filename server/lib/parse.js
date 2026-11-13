// פענוח ההתרעות מכל המקורות, ומניעת כפילויות - בלי צד-השפעות (בלי רשת, Firebase או קבצים),
// כדי שאפשר לבדוק אותם בבדיקות האוטומטיות (test/) לפני כל עדכון של השרת
const { ilTime } = require('./util');

const THREATS = { 0: 'ירי רקטות וטילים', 1: 'אירוע חומרים מסוכנים', 2: 'חדירת מחבלים', 3: 'רעידת אדמה', 4: 'חשש לצונאמי',
  5: 'חדירת כלי טיס עוין', 6: 'חשש לאירוע רדיולוגי', 7: 'חשש לאירוע כימי', 8: 'התרעות פיקוד העורף' };
const PRE = 'בדקות הקרובות צפויות להתקבל התרעות באזורך', END = 'האירוע הסתיים';

// ---- טלגרם ----
const decodeHtml = h => h.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')
  .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n)).replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
  .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&');
const SECONDS_RE = /\(\s*[\d\s]*(שניות|דקות|מיידי)[^)]*\)/g, DATE_RE = /\(\d{1,2}\/\d{1,2}\/\d{4}\).*$/, ENDED_IN_RE = /הסתיים ב(.+)/;
// מילים שמופיעות רק בחתימה / בכותרת של ערוץ ("צופר - צבע אדום", "רדאר 📡") - אף פעם לא ביישוב
const NOT_AREA = /צבע אדום|רדאר|כומתה|פיקוד העורף|התרעה|התראה|יישובים|ישובים|טלגרם/;
const skip = l => l.startsWith('אזור') || /מרחב המוגן|השוהים|הסתיים|בדקות הקרובות|\.|היכנסו|בהמשך ל|נעלו|הישארו|\|/.test(l) ||
  l.startsWith('עדכון') || l.startsWith('על תושבי');

/** הודעת טלגרם -> { title, areas } או null */
function telegram(text) {
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
  }).map(a => a.replace(SECONDS_RE, '').trim().replace(/\.+$/, '')).filter(a => a && a.length <= 40 && !NOT_AREA.test(a));
  return areas.length ? { title, areas } : null;
}

// ---- צופר ----
/** הודעה של צופר -> { title, areas } או null. cityIds: מזהה עיר של צופר -> שם אזור */
function tzofar(m, cityIds) {
  const d = m && m.data; if (!d) return null;
  if (m.type === 'ALERT' && !d.isDrill && THREATS[d.threat ?? 0] && Array.isArray(d.cities))
    return { title: THREATS[d.threat ?? 0], areas: d.cities };
  if (m.type === 'SYSTEM_MESSAGE' && (d.instructionType === 0 || d.instructionType === 1)) {
    const areas = (d.citiesIds || []).map(i => cityIds[i]).filter(Boolean);
    if (areas.length) return { title: d.instructionType === 0 ? PRE : END, areas };
  }
  return null;
}

// ---- פיקוד העורף ----
/** הקובץ החי -> { id, title, areas } או null (ריק / בלי מזהה / בלי יישובים / לא JSON) */
function orefLive(txt) {
  let j; try { j = JSON.parse(txt); } catch (_) { return null; }
  if (!j || !j.id) return null;
  return { id: String(j.id), title: j.title || 'התראה', areas: Array.isArray(j.data) ? j.data : [] };
}

/** הארכיון החדש (מקובץ לפי ימים) -> שורות בפורמט ההיסטוריה, או null אם זו לא תשובה בפורמט שלו */
function archiveRows(t) {
  if (!t.startsWith('{')) return null;
  const rows = [];
  for (const day of JSON.parse(t).alarmsGroupedByDate || []) for (const o of day.value || []) {
    const m = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(String(o.date || '')), tm = String(o.time || '');
    rows.push({ data: o.data, category_desc: o.category_Desc, date: o.date, time: o.time,
      alertDate: m && tm.length >= 8 ? `${m[3]}-${m[2]}-${m[1]}T${tm.slice(0, 8)}` : o.dateTime });
  }
  return rows;
}

/** שורות היסטוריה -> התרעות מ-90 השניות האחרונות, מקובצות לפי סוג + זמן: [{ title, at, areas }] */
function historyGroups(rows, now = Date.now()) {
  const by = new Map();
  for (const o of rows) {
    const at = ilTime(String(o.alertDate || ''));
    if (!at || now - at > 90 * 1000) continue;
    const title = o.title || o.category_desc || 'התראה';
    const k = title + '|' + at;
    if (!by.has(k)) by.set(k, { title, at, areas: [] });
    by.get(k).areas.push(o.data);
  }
  return [...by.values()];
}

// ---- כפילויות ושליחה ----
const DEDUP_MS = 2 * 60 * 1000;
/**
 * אותה התרעה מכמה מקורות נשלחת פעם אחת: יישוב שכבר נשלח עם אותה כותרת ב-2 הדקות האחרונות - לא שוב,
 * אלא אם זה מטח חדש (מזהה אחר של פיקוד העורף). sentAt / lastEid - המצב (Map), מתעדכן כאן.
 * מחזיר את היישובים לשליחה
 */
function fresh(sentAt, lastEid, title, areas, eid, now = Date.now()) {
  for (const [k, t] of sentAt) if (now - t > DEDUP_MS) { sentAt.delete(k); lastEid.delete(k); }
  const out = [...new Set(areas.map(a => String(a).trim()).filter(Boolean))].filter(a => {
    const k = title + '|' + a;
    if (!sentAt.has(k)) return true;
    if (!eid) return false;
    // כבר נשלח ממקור אחר (בלי מזהה) - זה אותו אירוע; זוכרים את המזהה שלו למטח הבא
    if (!lastEid.has(k)) { lastEid.set(k, eid); return false; }
    return lastEid.get(k) !== eid;
  });
  for (const a of out) { const k = title + '|' + a; sentAt.set(k, now); if (eid) lastEid.set(k, eid); }
  return out;
}

/** הודעה ל-Firebase מוגבלת בגודל - הרבה יישובים (כל הארץ) מתחלקים לכמה הודעות */
function chunks(areas, max = 3000) {
  const out = []; let cur = [];
  for (const a of areas) {
    if (Buffer.byteLength(JSON.stringify([...cur, a])) > max) { out.push(cur); cur = []; }
    cur.push(a);
  }
  if (cur.length) out.push(cur);
  return out;
}

module.exports = { THREATS, PRE, END, decodeHtml, telegram, tzofar, orefLive, archiveRows, historyGroups, fresh, chunks, DEDUP_MS };
