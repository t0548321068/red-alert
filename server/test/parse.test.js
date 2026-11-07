// בדיקות אוטומטיות לשרת: רצות לפני כל עדכון עצמי (deploy/selfupdate.sh) - אם משהו נשבר, השרת נשאר בגרסה הקודמת.
// הרצה ידנית: node --test test/
const test = require('node:test');
const assert = require('node:assert/strict');
const P = require('../lib/parse');
const { ilTime } = require('../lib/util');

// ---- טלגרם ----
test('טלגרם: פיקוד העורף', () => {
  assert.deepEqual(P.telegram('🚨 ירי רקטות וטילים (6/11/2026) 14:02\nאזור עוטף עזה\nשדרות, איבים, ניר עם (15 שניות)\nהיכנסו למרחב המוגן ושהו בו 10 דקות'),
    { title: 'ירי רקטות וטילים', areas: ['שדרות', 'איבים', 'ניר עם'] });
});
test('טלגרם: צופר', () => {
  assert.deepEqual(P.telegram('צבע אדום\n05:23: • עוטף עזה: כיסופים (15 שניות)'), { title: 'ירי רקטות וטילים', areas: ['כיסופים'] });
});
test('טלגרם: כומתה ורדאר', () => {
  assert.deepEqual(P.telegram('🔴 צבע אדום\n• עוטף עזה 231 - נירים, עין השלושה'), { title: 'ירי רקטות וטילים', areas: ['נירים', 'עין השלושה'] });
  assert.deepEqual(P.telegram('רדאר 📡\nירי רקטות\n• קו העימות\nשלומי, מטולה'), { title: 'ירי רקטות וטילים', areas: ['שלומי', 'מטולה'] });
});
test('טלגרם: סיום, מקדימה, כלי טיס', () => {
  assert.deepEqual(P.telegram('האירוע הסתיים בכפר יובל, דפנה'), { title: P.END, areas: ['כפר יובל', 'דפנה'] });
  assert.equal(P.telegram('בדקות הקרובות צפויות להתקבל התרעות באזורך\nשדרות, איבים').title, P.PRE);
  assert.equal(P.telegram('חדירת כלי טיס עוין\nקריית שמונה').title, 'חדירת כלי טיס עוין');
});
test('טלגרם: בלי יישובים - לא התרעה', () => {
  assert.equal(P.telegram(''), null);
  assert.equal(P.telegram('צבע אדום'), null);
});
test('טלגרם: HTML מהערוץ', () => {
  assert.equal(P.decodeHtml('צבע אדום<br/>שדרות &amp; איבים &#34;'), 'צבע אדום\nשדרות & איבים "');
});

// ---- צופר ----
const tz = (type, data) => ({ type, data });
test('צופר: ירי, תרגיל, הודעות מערכת', () => {
  assert.deepEqual(P.tzofar(tz('ALERT', { threat: 0, cities: ['שדרות', 'איבים'] }), {}), { title: 'ירי רקטות וטילים', areas: ['שדרות', 'איבים'] });
  assert.deepEqual(P.tzofar(tz('ALERT', { threat: 5, cities: ['מטולה'] }), {}).title, 'חדירת כלי טיס עוין');
  assert.equal(P.tzofar(tz('ALERT', { threat: 0, isDrill: true, cities: ['שדרות'] }), {}), null);
  const ids = { 1: 'שדרות, איבים', 2: 'נירים' };
  assert.deepEqual(P.tzofar(tz('SYSTEM_MESSAGE', { instructionType: 0, citiesIds: [1, 2, 99] }), ids), { title: P.PRE, areas: ['שדרות, איבים', 'נירים'] });
  assert.deepEqual(P.tzofar(tz('SYSTEM_MESSAGE', { instructionType: 1, citiesIds: [2] }), ids), { title: P.END, areas: ['נירים'] });
  assert.equal(P.tzofar(tz('SYSTEM_MESSAGE', { instructionType: 7, citiesIds: [2] }), ids), null);
  assert.equal(P.tzofar(tz('SYSTEM_MESSAGE', { instructionType: 0, citiesIds: [99] }), ids), null);
  assert.equal(P.tzofar({ type: 'ALERT' }, {}), null);
});

// ---- פיקוד העורף ----
test('פיקוד העורף: הקובץ החי', () => {
  assert.deepEqual(P.orefLive(JSON.stringify({ id: 133456789, title: 'ירי רקטות וטילים', data: ['שדרות, איבים', 'נירים'] })),
    { id: '133456789', title: 'ירי רקטות וטילים', areas: ['שדרות, איבים', 'נירים'] });
  assert.equal(P.orefLive(''), null);
  assert.equal(P.orefLive('<html>'), null);
  assert.equal(P.orefLive(JSON.stringify({ title: 'ירי' })), null);
});
test('פיקוד העורף: שעון ישראל', () => {
  assert.equal(ilTime('2026-11-06 14:02:10'), Date.UTC(2026, 10, 6, 12, 2, 10));   // חורף: UTC+2
  assert.equal(ilTime('2026-07-01T14:00:00'), Date.UTC(2026, 6, 1, 11, 0, 0));      // קיץ: UTC+3
  assert.equal(ilTime('לא תאריך'), 0);
});
test('פיקוד העורף: היסטוריה - קיבוץ, וחלון של 90 שניות', () => {
  const rows = [
    { alertDate: '2026-11-06 14:02:10', title: 'ירי רקטות וטילים', data: 'שדרות, איבים' },
    { alertDate: '2026-11-06 14:02:10', title: 'ירי רקטות וטילים', data: 'נירים' },
    { alertDate: '2026-11-06T14:02:40', category_desc: 'חדירת כלי טיס עוין', data: 'כיסופים' },
    { alertDate: '2026-11-06 13:00:00', title: 'ירי רקטות וטילים', data: 'ישן' },
  ];
  const g = P.historyGroups(rows, ilTime('2026-11-06 14:03:00'));
  assert.equal(g.length, 2);
  assert.deepEqual(g[0], { title: 'ירי רקטות וטילים', at: ilTime('2026-11-06 14:02:10'), areas: ['שדרות, איבים', 'נירים'] });
  assert.equal(g[1].title, 'חדירת כלי טיס עוין');
});
test('פיקוד העורף: הארכיון החדש', () => {
  const t = JSON.stringify({ alarmsGroupedByDate: [{ value: [{ date: '06.11.2026', time: '14:02:10', data: 'נירים', category_Desc: 'ירי רקטות וטילים' }] }] });
  const rows = P.archiveRows(t);
  assert.equal(rows[0].alertDate, '2026-11-06T14:02:10');
  assert.equal(P.historyGroups(rows, ilTime('2026-11-06 14:03:00'))[0].title, 'ירי רקטות וטילים');
  assert.equal(P.archiveRows('<html>'), null);
});

// ---- כפילויות ----
test('כפילויות: אותה התרעה מכמה מקורות נשלחת פעם אחת', () => {
  const s = new Map(), e = new Map(), t0 = 1_762_437_600_000;
  assert.deepEqual(P.fresh(s, e, 'ירי רקטות וטילים', ['נירים', ' נירים ', ''], '133', t0), ['נירים']);
  assert.deepEqual(P.fresh(s, e, 'ירי רקטות וטילים', ['נירים'], '', t0 + 2000), []);       // צופר / טלגרם
  assert.deepEqual(P.fresh(s, e, 'ירי רקטות וטילים', ['נירים'], '133', t0 + 3000), []);    // שוב פיקוד העורף
  assert.deepEqual(P.fresh(s, e, 'ירי רקטות וטילים', ['נירים', 'כיסופים'], '', t0 + 4000), ['כיסופים']);
});
test('כפילויות: מטח חדש', () => {
  const s = new Map(), e = new Map(), t0 = 1_762_437_600_000;
  P.fresh(s, e, 'ירי רקטות וטילים', ['נירים'], '133', t0);
  assert.deepEqual(P.fresh(s, e, 'ירי רקטות וטילים', ['נירים'], '134', t0 + 40000), ['נירים']);   // מזהה חדש
  P.fresh(s, e, 'ירי רקטות וטילים', ['כיסופים'], '', t0);
  assert.deepEqual(P.fresh(s, e, 'ירי רקטות וטילים', ['כיסופים'], '', t0 + 121000), ['כיסופים']); // אחרי 2 דקות
  // צופר היה ראשון (בלי מזהה), אחריו פיקוד העורף - אותו אירוע; המזהה הבא - מטח חדש
  const s2 = new Map(), e2 = new Map();
  P.fresh(s2, e2, 'ירי רקטות וטילים', ['נירים'], '', t0);
  assert.deepEqual(P.fresh(s2, e2, 'ירי רקטות וטילים', ['נירים'], '200', t0 + 5000), []);
  assert.deepEqual(P.fresh(s2, e2, 'ירי רקטות וטילים', ['נירים'], '201', t0 + 50000), ['נירים']);
});
test('כפילויות: כותרת אחרת - לא כפילות', () => {
  const s = new Map(), e = new Map(), t0 = 1_762_437_600_000;
  P.fresh(s, e, 'ירי רקטות וטילים', ['נירים'], '', t0);
  assert.deepEqual(P.fresh(s, e, P.END, ['נירים'], '', t0 + 1000), ['נירים']);
});

// ---- חלוקה להודעות ----
test('כל הארץ: מתחלק להודעות קטנות, בלי לאבד יישוב', () => {
  const many = Array.from({ length: 1400 }, (_, i) => 'יישוב לבדיקה מספר ' + i);
  const parts = P.chunks(many);
  assert.ok(parts.length > 1);
  assert.deepEqual(parts.flat(), many);
  for (const p of parts) assert.ok(Buffer.byteLength(JSON.stringify(p)) <= 3000);
  assert.deepEqual(P.chunks(['נירים']), [['נירים']]);
});
