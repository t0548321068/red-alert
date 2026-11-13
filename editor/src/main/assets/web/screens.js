// המסכים של צופר חירום (העתק לפי הקוד של גרסה 4.25b), עם נתונים לדוגמה.
// כל מסך: { id, name, group, bg, build() } - build מחזיר HTML. H = גובה המסך (בלי פס העריכה)
'use strict';

let H = 700;
const AREA = 'ירושלים - צפון';
const PRE = 1, ALERT = 0, END = 2;
const LEVEL_COLOR = { 0: '#D50000', 1: '#F08C00', 2: '#2E7D32' };
const W = '#FFFFFF', W90 = '#E6FFFFFF';

/** צבע ההתרעה מעורבב ברקע (applyMainTint): 30% בחשוך, 18% בבהיר */
function tint(level) {
  const a = LEVEL_COLOR[level], f = C.LIGHT ? 0.18 : 0.30;
  const h = x => parseInt(x, 16);
  const mix = (i) => Math.round(h(a.substr(i, 2)) * f + h(C.BG.substr(i, 2)) * (1 - f));
  return `rgb(${mix(1)},${mix(3)},${mix(5)})`;
}

// ===================== המסך הראשי =====================

/** חיווי בשורת החיוויים: אייקון בצבע המצב, ומתחת כיתוב 12 */
function ind(name, label, color, n) {
  return box(icon(name, 26, color, { n: 'אייקון ' + n }) +
    t(label, 12, C.TEXT, { center: true, n: 'כיתוב ' + n, st: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%;margin-top:1px' }),
    'flex:1;min-width:0;display:flex;flex-direction:column;align-items:center', { n: 'חיווי ' + n });
}
function statusRow() {
  const items = [
    ind('ic_shield_ind', 'מוגן', C.GREEN, 'מוגן'), ind('ic_snd_vib', 'צלילים', C.GREEN, 'צלילים'),
    ind('ic_globe', 'Wi-Fi', C.GREEN, 'רשת'), ind('ic_location', '12 מ׳', C.GREEN, 'מיקום'),
    ind('ic_antenna', 'מחובר', C.GREEN, 'חיבור'), ind('ic_info', 'v4.25b', C.GREEN, 'גרסה')];
  const sep = `<div style="width:1px;height:26px;background:${css(C.LINE2)};flex:none"></div>`;
  return box(items.join(sep), `display:flex;align-items:center;padding:5px 6px;background:${css(C.CARD2)};border-radius:28px;margin-top:12px`,
    { n: 'שורת החיוויים' });
}

/** כרטיס השעון (מוגן): מגן, "מוגן", אזור ההתרעה, השעה, היום, מזג האוויר */
function clockCard() {
  const dark = !C.LIGHT;
  const halo = box(
    box('', 'position:absolute;inset:0;border-radius:50%;background:' + css('#0F69F0AE'), { n: 'הילה חיצונית' }) +
    box(icon('ic_shield_green', 26, null, { n: 'המגן' }),
      'position:absolute;left:7px;top:7px;width:42px;height:42px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:' + css('#2469F0AE'),
      { n: 'עיגול המגן' }),
    'position:relative;width:56px;height:56px;margin:0 auto', { n: 'המגן וההילה' });
  const area =
    t('אזור התרעה', 17, C.SOFT, { center: true }) +
    t(AREA, 26, C.TEXT, { bold: true, center: true, pad: '2px 0 0', n: 'שם האזור' }) +
    t('⏱ זמן התגוננות: דקה וחצי', 17, C.SOFT, { center: true, pad: '2px 0 0', n: 'זמן התגוננות' }) +
    box('', 'height:1px;background:' + css('#33FFFFFF') + ';margin-top:10px', { n: 'קו מתחת לאזור' });
  const inner =
    t(greeting() + ' טל', 20, C.SOFT, { center: true, st: 'margin-bottom:6px', n: 'ברכה' }) + halo +
    t('מוגן', 22, '#69F0AE', { bold: true, center: true, st: 'margin-bottom:2px', n: 'מוגן' }) +
    box(area, 'margin:4px 0 10px', { n: 'אזור ההתרעה' }) +
    t(nowHM(false), 46, C.TEXT, { bold: true, center: true, n: 'השעה', st: 'line-height:1.15' }) +
    t(dayLine(), 19, C.SOFT, { center: true, n: 'היום והתאריך' }) +
    t('⛅ 14° · פסגת זאב', 19, C.SOFT, { center: true, st: 'margin-top:4px', n: 'מזג אוויר' });
  return box(inner, `margin-top:12px;padding:16px;border-radius:28px;box-sizing:border-box;background:${dark ? '#12301A' : '#E3F4E5'};border:2px solid #4CAF50`,
    { n: 'כרטיס השעון' });
}

/** כרטיס היישובים והאזורים הנוספים (תצוגה בלבד) */
function extraCard() {
  const r = (name, sub) => box(t(name, 15, C.TEXT, { st: 'flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis', n: 'יישוב: ' + name }) +
    (sub ? t(sub, 12, C.MUTED, { st: 'margin-inline-start:10px', n: 'אזור של ' + name }) : ''),
    'display:flex;align-items:center;padding:5px 18px', { n: 'שורת יישוב: ' + name });
  const inner = t('יישובים ואזורים נוספים', 16, C.TEXT, { bold: true, pad: '0 18px 6px', n: 'כותרת אזורים נוספים' }) +
    t('יישובים', 12, C.MUTED2, { pad: '6px 18px 2px', n: 'כותרת יישובים' }) + r('תל אביב - מרכז העיר', 'דן') + r('מעלה אדומים', 'ירושלים') +
    t('אזורים', 12, C.MUTED2, { pad: '6px 18px 2px', n: 'כותרת אזורים' }) + r('עוטף עזה', '');
  return box(inner, `background:${css(C.CARD2)};border-radius:28px;padding:14px 0 10px`, { n: 'כרטיס אזורים נוספים' });
}

/** הפיד הארצי בתחתית */
function feedCard() {
  const line = `<div style="height:1px;background:${css(C.LINE2)};margin:0 14px"></div>`;
  const ic = icon('ic_alert_rocket', 17, LEVEL_COLOR[0], { n: 'אייקון בפיד', st: 'display:inline-block;vertical-align:middle' });
  const txt = box(`${nowHM(false)}<br>${ic} ירי רקטות וטילים ${ic}<br>שדרות, איבים, ניר עם`,
    `font-size:14px;color:${css(C.TEXT)};text-align:center;padding:10px 12px 14px;line-height:1.4`, { n: 'טקסט הפיד' });
  return box(t('התרעות אחרונות', 15, C.TEXT, { bold: true, center: true, pad: '12px 12px 10px', n: 'כותרת הפיד' }) + line + txt,
    `background:${css(C.CARD2)};border-radius:28px;overflow:hidden;margin-top:12px`, { n: 'כרטיס הפיד הארצי' });
}

/** כרטיס ההתרעה מעל השעון (סגנון "כרטיס") */
function mainAlertCard(level) {
  const head = level === PRE ? ['מבזק', 'התרעה מקדימה'] : level === END ? ['עדכון', 'האירוע הסתיים'] : ['ירי רקטות וטילים', 'היכנסו למרחב המוגן'];
  const ic = level === PRE ? 'ic_list_pre' : level === END ? 'ic_list_end' : 'ic_alert_rocket';
  let inner = `<div style="display:flex;justify-content:center;margin-bottom:2px">${icon(ic, 34, null, { n: 'אייקון ההתרעה' })}</div>` +
    t(head[0], 25, W, { bold: true, center: true, n: 'כותרת ההתרעה' }) +
    t(head[1], 19, W90, { center: true, n: 'שורה מתחת לכותרת' }) +
    `<div style="display:flex;justify-content:center;margin:8px 0 2px">` +
    t(AREA, 19, W, { bold: true, center: true, pad: '6px 16px', st: 'background:' + css('#33FFFFFF') + ';border-radius:20px', n: 'האזור' }) + '</div>';
  if (level === ALERT) inner += t('זמן התגוננות', 13, W90, { center: true, pad: '4px 0 0', n: 'כיתוב זמן התגוננות' }) +
    t('1:23', 38, W, { bold: true, center: true, st: 'line-height:1', n: 'ספירה לאחור' });
  inner += t('מקור ההתרעה: פיקוד העורף', 12, W90, { center: true, pad: '5px 0 0', n: 'מקור ההתרעה' });
  const x = box(icon('ic_close', 16, null, { n: 'אייקון סגירה' }),
    'position:absolute;top:12px;left:12px;width:32px;height:32px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:' + css('#40000000'),
    { n: 'כפתור סגירה ✕' });
  return box(box(inner, `background:${LEVEL_COLOR[level]};border-radius:28px;padding:14px 16px`, { n: 'כרטיס ההתרעה' }) + x,
    'position:relative;margin-top:12px');
}

/** החלון הממוזער (גלולה) */
function miniBar(level, inMain) {
  const ic = level === PRE ? 'ic_list_pre' : level === END ? 'ic_list_end' : 'ic_alert_rocket';
  const label = level === PRE ? 'התרעה מקדימה' : level === END ? 'האירוע הסתיים' : 'ירי רקטות וטילים';
  let timer;
  if (level === ALERT) timer = t('זמן התגוננות', 10, W90, { center: true, n: 'כיתוב זמן התגוננות' }) + t('1:23', 19, W, { bold: true, center: true, st: 'line-height:1', n: 'ספירה לאחור' });
  else timer = t(level === PRE ? 'בדקות הקרובות צפויות\nלהתקבל התרעות באזורך' : 'ניתן לצאת\nמהמרחב המוגן', 12, W, { bold: true, center: true, st: 'line-height:1.15', n: 'טקסט בצד' });
  const inner = `<div style="margin-inline-start:6px">${icon(ic, 28, W, { n: 'אייקון ההתרעה' })}</div>` +
    box(t(label, 12, W90, { n: 'כותרת', st: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }) +
      t(AREA, 15, W, { bold: true, n: 'האזור', st: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }), 'flex:1;min-width:0;padding:0 10px 0 6px') +
    box(timer, 'padding:0 4px 0 8px;display:flex;flex-direction:column;align-items:center', { n: 'אזור הספירה' }) +
    box(icon('ic_close', 17, null, { n: 'אייקון סגירה' }),
      'width:34px;height:34px;border-radius:50%;display:flex;align-items:center;justify-content:center;flex:none;background:' + css('#38000000'), { n: 'כפתור סגירה ✕' });
  return box(inner, `display:flex;align-items:center;padding:8px;border-radius:28px;background:${LEVEL_COLOR[level]};box-shadow:0 3px 8px rgba(0,0,0,.4);${inMain ? 'margin-top:12px' : ''}`,
    { n: 'החלון הממוזער' });
}

function testFab() {
  return box(icon('ic_list_siren', 30, W, { n: 'אייקון הכפתור הצף' }),
    'position:absolute;left:20px;bottom:20px;width:56px;height:56px;border-radius:50%;background:#D50000;display:flex;align-items:center;justify-content:center;box-shadow:0 3px 8px rgba(0,0,0,.5)',
    { n: 'כפתור בדיקה צף' });
}

/** המסך הראשי. alert: כרטיס התרעה מעל השעון; mini: החלון הממוזער במקום הכרטיס */
function mainScreen(o = {}) {
  const header = box(t('צופר חירום', 24, C.TEXT, { bold: true, pad: '0 4px', n: 'כותרת "צופר חירום"', st: 'flex:1' }) +
    headerIcon('ic_nav_clock', 'שעון') + headerIcon('ic_nav_alerts', 'התרעות') + headerIcon('ic_gear', 'הגדרות'),
    'display:flex;align-items:center;margin-top:6px', { n: 'שורת הכותרת' });
  let col = header + statusRow();
  if (o.alert != null) col += mainAlertCard(o.alert);
  if (o.mini != null) col += miniBar(o.mini, true);
  col += clockCard();
  col += `<div style="flex:1;margin-top:12px;min-height:${o.alert != null || o.mini != null ? 0 : 60}px">${extraCard()}</div>`;
  col += feedCard();
  const bg = o.alert != null || o.mini != null ? tint(o.alert != null ? o.alert : o.mini) : css(C.BG);
  return `<div style="position:relative;min-height:100%;display:flex;flex-direction:column;box-sizing:border-box;padding:4px 16px 12px;background:${bg}">${col}${testFab()}</div>`;
}

// ===================== מסכי ההתרעה (AlertActivity) =====================

function mapBox(level, h) {
  const fill = { 0: 'rgba(255,59,48,.45)', 1: 'rgba(255,159,10,.45)', 2: 'rgba(48,209,88,.45)' }[level];
  const stroke = { 0: '#FF3B30', 1: '#FF9F0A', 2: '#30D158' }[level];
  const bg = C.LIGHT ? '#F5F1E8' : '#2C3042', road = C.LIGHT ? '#ffffff' : '#3d4257';
  // רוחב 360 וגובה לפי המקום - האזור תמיד באותו גודל, באמצע
  const vh = Math.round(360 * h / 412), cy = vh / 2;
  const poly = [[130, -40], [215, -52], [250, -5], [225, 50], [150, 55], [115, 10]].map(([x, y]) => x + ' ' + (cy + y)).join(' L');
  const svg = `<svg width="100%" height="100%" viewBox="0 0 360 ${vh}">
    <rect width="360" height="${vh}" fill="${bg}"/>
    <path d="M0 ${cy - 40} L360 ${cy - 10} M40 0 L120 ${vh} M220 0 L260 ${vh} M0 ${cy + 50} L360 ${cy + 30} M300 0 L330 ${vh}" stroke="${road}" stroke-width="5"/>
    <path d="M${poly} Z" fill="${fill}" stroke="${stroke}" stroke-width="2.5"/>
    <text x="182" y="${cy + 6}" text-anchor="middle" font-size="13" fill="${C.LIGHT ? '#333' : '#ddd'}">${AREA}</text></svg>`;
  return box(svg, `height:${h}px;overflow:hidden`, { n: 'המפה' });
}

/** מסך התרעה מלא (popup=false) או פופ-אפ (popup=true) */
function alertScreen(level, popup) {
  const bg = LEVEL_COLOR[level];
  const pre = level === PRE, end = level === END;
  const head = pre ? (popup ? ['מבזק', 'התרעה מקדימה'] : ['מבזק - התקרבו למרחב המוגן', 'התרעה מקדימה'])
    : end ? ['עדכון', 'האירוע הסתיים'] : ['ירי רקטות וטילים', 'היכנסו למרחב המוגן'];
  const ic = pre ? 'ic_list_pre' : end ? 'ic_list_end' : 'ic_alert_rocket';
  let s = box(`<div style="display:flex;justify-content:center">${icon(ic, 36, null, { n: 'אייקון ההתרעה' })}</div>` +
    t(head[0], 28, W, { bold: true, center: true, n: 'כותרת ההתרעה' }) +
    t(head[1], popup ? 20 : 22, W, { center: true, pad: '2px 0 0', n: 'שורה מתחת לכותרת' }),
    `padding:${popup ? 16 : 12}px 16px ${popup ? 10 : 14}px`, { n: 'ראש המסך' });
  s += mapBox(level, popup ? 170 : Math.round(H * 0.34));
  s += box(t(AREA, 22, W, { bold: true, center: true, pad: '6px 14px', st: 'background:' + css('#33FFFFFF') + ';border-radius:18px', n: 'תגית אזור' }),
    'margin:10px 10px 0;padding:0 6px;display:flex;flex-wrap:wrap;justify-content:center;gap:8px', { n: 'האזורים' });
  if (end) s += t('ניתן לצאת מהמרחב המוגן', 19, W, { center: true, pad: '14px 16px 0', n: 'ניתן לצאת' });
  if (pre) {
    s += t('בדקות הקרובות צפויות להתקבל התרעות באזורך', 19, W, { center: true, pad: '14px 16px 0', n: 'נוסח המבזק' });
    s += t('במקרה של קבלת התרעה, יש להיכנס למרחב המוגן ולשהות בו עד לקבלת הנחיה מפורשת', 19, W, { center: true, pad: '6px 16px 0', n: 'הנחיה במבזק' });
  }
  if (level === ALERT) {
    if (popup) s += box(t('זמן התגוננות', 13, W, { center: true, n: 'כיתוב זמן התגוננות' }) +
      t('1:23', 38, W, { bold: true, center: true, st: 'line-height:1', n: 'ספירה לאחור' }), 'margin:4px 10px 0;padding:0 12px', { n: 'הטיימר' });
    else s += box(t('זמן התגוננות: דקה וחצי', 22, W, { n: 'כיתוב זמן התגוננות' }) + '<div style="flex:1"></div>' +
      t('1:23', 22, W, { bold: true, n: 'ספירה לאחור' }), 'display:flex;align-items:center;margin:4px 10px 0;padding:0 20px', { n: 'הטיימר' });
  }
  s += '<div style="flex:1"></div>';
  s += t('מקור ההתרעה: פיקוד העורף', 13, W90, { center: true, pad: '6px 0 0', st: 'margin:8px 16px 0', n: 'מקור ההתרעה' });
  const btn = (ic, label) => box(icon(ic, 20, null, { n: 'אייקון ' + label }) + t(label, 13, W, { center: true, st: 'margin-top:3px', n: 'כיתוב ' + label }),
    'flex:1;display:flex;flex-direction:column;align-items:center;padding:9px 0;border-radius:24px;background:' + css('#40000000'), { n: 'כפתור ' + label });
  s += box([btn('ic_volume_off', 'השתק'), btn('ic_info_line', 'הנחיות'), btn('ic_close', 'סגור')].join(''),
    `display:flex;gap:8px;padding:10px 10px ${popup ? 12 : 16}px`, { n: 'שורת הכפתורים' });
  if (!popup) return `<div style="min-height:100%;display:flex;flex-direction:column;background:${bg}">${s}</div>`;
  const ph = Math.min(560, H - 48);
  return `<div style="min-height:100%;background:${css(C.BG)};position:relative">` +
    `<div style="position:absolute;inset:0;background:rgba(0,0,0,.55)"></div>` +
    `<div style="position:relative;padding:24px 14px;min-height:${H}px;box-sizing:border-box;display:flex;align-items:center">` +
    box(s, `width:100%;height:${ph}px;display:flex;flex-direction:column;background:${bg};border-radius:28px;overflow:hidden;box-shadow:0 8px 24px rgba(0,0,0,.6)`, { n: 'חלון הפופ-אפ' }) +
    '</div></div>';
}

/** ממוזער מעל אפליקציה אחרת: הגלולה למעלה, מתחת מסך כללי */
function miniOver(level) {
  return `<div style="min-height:100%;background:${C.LIGHT ? '#E8E8EC' : '#1a1a1a'};padding:8px 14px;box-sizing:border-box">${miniBar(level, false)}` +
    t('(אפליקציה אחרת פתוחה מתחת)', 13, C.MUTED, { center: true, pad: '40px 0 0', n: 'הסבר' }) + '</div>';
}

// ===================== התרעות אחרונות =====================

function recentRow(level, title, body, time) {
  const col = level === PRE ? '#F08C00' : level === END ? '#2E7D32' : '#E53935';
  const ic = level === PRE ? 'ic_list_pre' : level === END ? 'ic_list_end' : title.includes('כלי טיס') ? 'ic_alert_aircraft' : 'ic_alert_rocket';
  const sc = level === PRE || level === END ? 1.4 : 1;
  return box(box(icon(ic, 18, null, { n: 'אייקון: ' + title, st: `transform:scale(${sc})` }),
      `width:36px;height:36px;border-radius:50%;background:${col};display:flex;align-items:center;justify-content:center;flex:none;margin-inline-end:12px`, { n: 'עיגול: ' + title }) +
    box(t(title, 15, C.TEXT, { n: 'סוג: ' + title }) + t(body, 12, C.MUTED2, { lines: 2, n: 'אזורים: ' + title }), 'flex:1;min-width:0') +
    t(time, 12, C.MUTED2, { st: 'margin-inline-start:8px', n: 'שעה: ' + title }),
    'display:flex;align-items:center;padding:12px 16px', { n: 'שורת התרעה: ' + title });
}
function recentPage() {
  const seg = box(t('הכול (1,284)', 14, W, { bold: true, center: true, pad: '7px 0', st: 'min-width:110px;background:#4C7DFF;border-radius:16px', n: 'לשונית הכול' }) +
    t('שלי (37)', 14, C.MUTED, { center: true, pad: '7px 0', st: 'min-width:110px', n: 'לשונית שלי' }),
    `display:flex;background:${css(C.CARD2)};border-radius:28px;padding:3px`, { n: 'בורר הכול / שלי' });
  const day = (s) => t(s, 13, C.MUTED2, { pad: `14px ${PAGE_SIDE + 4}px 6px`, n: 'כותרת יום' });
  const dv = `<div style="height:1px;background:${css(C.DIV)}"></div>`;
  const cardOf = rows => `<div style="margin:0 ${PAGE_SIDE}px">${card(rows.join(dv), { n: 'כרטיס יום' })}</div>`;
  const body = day('היום · ' + dateDMY()) + cardOf([
    recentRow(END, 'האירוע הסתיים', 'שדרות, איבים, ניר עם', '16:52:10'),
    recentRow(ALERT, 'ירי רקטות וטילים', 'שדרות, איבים, ניר עם, אור הנר, גבים', '16:42:03'),
    recentRow(PRE, 'התרעה מקדימה', 'עוטף עזה, מערב הנגב', '16:39:40')]) +
    day('אתמול · 12/11/2026') + cardOf([
    recentRow(ALERT, 'חדירת כלי טיס עוין', 'קריית שמונה, מרגליות', '22:10:55'),
    recentRow(END, 'האירוע הסתיים', 'קריית שמונה, מרגליות', '22:31:00')]);
  return ouiPage('התרעות אחרונות',
    `<div style="display:flex;justify-content:center;padding-bottom:4px">${seg}</div>${body}`, headerIcon('ic_map', 'מפה'));
}

/** לחיצה על התרעה ברשימה: פס צבעוני, מפה, ומסך תחתון עם הפרטים */
function alertDetail(level) {
  const kind = level === PRE ? 'מבזק - התרעה מקדימה' : level === END ? 'עדכון - סיום אירוע' : 'ירי רקטות וטילים';
  const title = level === PRE ? 'איום מלבנון - התקרבו למרחב המוגן' : level === END ? 'האירוע הסתיים' : 'ירי רקטות וטילים';
  const dark = !C.LIGHT;
  const sheetBg = dark ? '#121212' : '#F2F2F7', cardBg = dark ? '#1E1E1E' : '#FFFFFF', ink = dark ? '#F2F2F7' : '#1C1C1E', muted = dark ? '#A0A0A5' : '#6E6E73';
  const accent = { 0: '#FF3B30', 1: '#FF9F0A', 2: '#30D158' }[level];
  const bar = box(t(kind, 19, W, { bold: true, pad: '0 8px', n: 'כותרת הפס' }), `height:60px;display:flex;align-items:center;padding:0 12px;background:${LEVEL_COLOR[level]}`, { n: 'פס הכותרת' });
  const head = box(`<div style="width:40px;height:4px;border-radius:2px;background:${muted};opacity:.5;margin:0 auto 12px"></div>` +
    t(title, 19, ink, { bold: true, n: 'כותרת ההתרעה' }) + t('קריית שמונה, אצבע הגליל', 16, ink, { pad: '4px 0 0', n: 'האזורים' }) +
    t(dateDMY() + '  16:42', 14, muted, { pad: '4px 0 0', st: 'direction:ltr;text-align:left', n: 'תאריך ושעה' }) +
    t('מקור ההתרעה: פיקוד העורף', 14, muted, { center: true, pad: '6px 0 0', n: 'מקור ההתרעה' }), 'padding:10px 20px', { n: 'ראש המסך התחתון' });
  const grp = box(box(t(title, 13, muted, { st: 'flex:1', n: 'כותרת בכרטיס' }) + t('16:42:03', 13, muted, { n: 'שעה בכרטיס' }), 'display:flex') +
    t('קו העימות', 17, accent, { bold: true, pad: '6px 0 0', n: 'שם האזור בכרטיס' }) + t('קריית שמונה, מרגליות, משגב עם', 15, ink, { pad: '2px 0 0', n: 'יישובים בכרטיס' }),
    `padding:12px 16px 14px;background:${cardBg};border-radius:18px;margin-bottom:10px`, { n: 'כרטיס שעת הגעה' });
  const sheet = box(head + `<div style="padding:4px 14px 24px">${grp}</div>`,
    `position:absolute;left:0;right:0;bottom:0;background:${sheetBg};border-radius:24px 24px 0 0;box-shadow:0 -4px 12px rgba(0,0,0,.4)`, { n: 'המסך התחתון' });
  return `<div style="min-height:100%;display:flex;flex-direction:column;background:${css(C.BG)}">${bar}<div style="position:relative;flex:1;min-height:${H - 60}px">${mapBox(level, H - 60)}${sheet}</div></div>`;
}

// ===================== הגדרות =====================

function settingsMain() {
  const ICONC = { 'הגנה': '#D50000', 'תצוגה': '#7E57C2', 'התרעות': '#FB8C00', 'הרשאות': '#00897B', 'עדכונים': '#1E88E5', 'אודות': '#546E7A' };
  const ICON = { 'הגנה': 'ic_set_shield', 'תצוגה': 'ic_set_display', 'התרעות': 'ic_set_bell', 'הרשאות': 'ic_set_lock', 'עדכונים': 'ic_set_update', 'אודות': 'ic_set_info' };
  const r = (title, on) => {
    let inner = box(icon(ICON[title], 20, null, { n: 'אייקון ' + title }),
      `width:34px;height:34px;border-radius:50%;background:${ICONC[title]};display:flex;align-items:center;justify-content:center;flex:none;margin-inline-end:14px`, { n: 'עיגול ' + title }) +
      t(title, 16, C.TEXT, { st: 'flex:1', n: title });
    if (on != null) inner += `<div style="display:flex;align-items:center;align-self:stretch;padding-inline-start:12px"><div style="width:1px;height:20px;background:${css(C.SEPV)};margin-inline-end:12px"></div>${sw(on)}</div>`;
    return `<div style="margin:0 ${PAGE_SIDE}px 12px">${card(box(inner, 'display:flex;align-items:center;padding:12px 16px', { n: 'שורה: ' + title }), { n: 'כרטיס ' + title })}</div>`;
  };
  return ouiPage('הגדרות', r('הגנה', true) + r('תצוגה') + r('התרעות') + r('הרשאות') + r('עדכונים') + r('אודות'));
}

function protectionPage() {
  return ouiPage('הגנה', add(row('הגנה', { on: true })) +
    add(row('תזמון', { sub: 'פעיל רק בימים ובשעות שתבחר', on: false })) + note('כשהתזמון כבוי - ההגנה פעילה כל הזמן'));
}

function displayPage() {
  const preview = (light, label, sel) => box(
    box([12, 26, 40, 26].map(h => `<div style="height:${h}px;border-radius:6px;margin-bottom:5px;background:${light ? '#BDBDC2' : '#3E3E42'}"></div>`).join(''),
      `width:76px;height:150px;box-sizing:border-box;padding:6px;border-radius:12px;background:${light ? '#D9D9DC' : '#2B2B2E'};margin:0 auto`, { n: 'הדמיה ' + label }) + labelRadio(label, sel),
    'flex:1;display:flex;flex-direction:column;align-items:center', { n: 'בחירה ' + label });
  const theme = box(preview(false, 'חשוך', !C.LIGHT) + preview(true, 'בהיר', C.LIGHT), 'display:flex;padding:20px 10px 16px', { n: 'בחירת ערכת צבעים' }) +
    divider() + row('לפי המערכת', { on: false }) + row('לפי זמן', { sub: 'משקיעה עד זריחה', on: false, toggle: true });
  return ouiPage('תצוגה',
    add(theme) + note('לפי המערכת – בהיר או חשוך לפי הגדרת הטלפון\nלפי זמן – חשוך בלילה ובהיר ביום') +
    add(row('שורת חיוויים', { sub: 'תמיד', on: true, toggle: true })) + note('מוגן · צלילים · רשת · מיקום · חיבור · גרסה – מעל השעון במסך הראשי') +
    add(row('שעון', { sub: 'נקודתיים מהבהבות' }) + divider() + row('תאריך', { sub: 'יום שני', on: true, toggle: true }) + divider() +
      row('מזג אוויר', { sub: 'עדכון כל 15 דקות', on: true, toggle: true }) + divider() + row('ברכה', { sub: 'טל', on: true, toggle: true })) +
    note('מזג אוויר וברכה בכרטיס השעון במסך הראשי') +
    add(row('פיד ארצי', { sub: '24 שעות', on: true, toggle: true })) +
    note('ההתרעות בכל הארץ בתחתית המסך הראשי. כשהמתג מופעל - הפיד מוצג תמיד, גם כשאין התרעות'));
}

function clockPage() {
  return ouiPage('שעון', add(row('הצגת שניות', { on: false }) + divider() + row('נקודתיים מהבהבות', { on: true })) + note('השינוי נראה מיד בשעון במסך הראשי'));
}

function alertSettingsPage() {
  return ouiPage('התרעות',
    add(row('סגנון התרעה', { sub: 'מסך מלא' })) +
    add(row('אזור התרעה', { sub: 'פעיל', on: true, toggle: true }) + divider() + row('מרחק מקסימלי', { sub: 'באזורך' }) + divider() +
      row('אזורים נוספים', { sub: 'תל אביב - מרכז העיר, מעלה אדומים, עוטף עזה' }) + divider() + row('התראה מהפיד הארצי', { sub: 'כבוי', on: false, toggle: true })) +
    add(row('צלילים ורטט')) + add(row('בדיקת התרעה')));
}

function stylePreview(kind) {
  const bg = C.LIGHT ? '#D9D9DC' : '#2B2B2E', block = C.LIGHT ? '#BDBDC2' : '#3E3E42', red = '#6E6E73', map = C.LIGHT ? '#BDBDC2' : '#333333';
  let list = [10, 20, 30, 20].map((h, i) => `<div style="height:${h}px;border-radius:4px;margin-bottom:4px;background:${(kind === 3 && i === 0) || (kind === 2 && i === 1) ? red : block}"></div>`).join('');
  let over = '';
  if (kind === 0) over = `<div style="position:absolute;inset:0;background:${red}"><div style="height:7px;border-radius:3px;background:${css('#B3FFFFFF')};margin:8px 10px 4px"></div><div style="height:30px;background:${map}"></div><div style="height:18px;border-radius:5px;background:${css('#40000000')};margin:6px 8px 0"></div></div>`;
  if (kind === 1) over = `<div style="position:absolute;left:6px;right:6px;top:50%;transform:translateY(-50%);background:${red};border-radius:8px;overflow:hidden"><div style="height:5px;border-radius:2px;background:${css('#B3FFFFFF')};margin:6px 8px 4px"></div><div style="height:18px;background:${map}"></div><div style="height:12px;border-radius:3px;background:${css('#40000000')};margin:5px 6px 6px"></div></div>`;
  return box(`<div style="padding:5px">${list}</div>${over}`, `position:relative;width:58px;height:116px;border-radius:10px;overflow:hidden;background:${bg};margin:0 auto`, { n: 'הדמיה' });
}
function stylePage() {
  const names = ['מסך מלא', 'פופ-אפ', 'כרטיס', 'ממוזער'];
  const opts = box(names.map((n, i) => box(stylePreview(i) + labelRadio(n, i === 0), 'flex:1', { n: 'בחירה ' + n })).join(''), 'display:flex;padding:20px 6px 16px', { n: 'בחירת סגנון' });
  return ouiPage('סגנון התרעה',
    add(opts + divider() + row('תצוגה מקדימה', { sub: 'רק איך זה נראה – בלי צליל ורטט' })) +
    add(row('התאמה אישית', { sub: 'סגנון נפרד לכל נושא', on: false })) + note('כשמופעל – הסימון למעלה מתבטל, ולכל נושא בוחרים סגנון משלו') +
    add(row('אחרי סגירה', { sub: 'כרטיס' })) + note('מה מוצג כשסוגרים התרעה במסך מלא או בפופ-אפ') +
    add(row('הצג מפה', { on: true })) + note('המפה מוצגת במסך מלא ובפופ-אפ'));
}

function soundPage() {
  return ouiPage('צלילים ורטט',
    add(row('השתקה זמנית', { sub: 'כבוי', on: false, toggle: true })) + note('כל ההתרעות מתקבלות בלי צליל ורטט – לזמן שתבחר') +
    add(row('צלילים', { sub: 'פעיל', on: true, toggle: true }) + divider() + row('רטט', { sub: 'פעיל', on: true, toggle: true }) + divider() +
      row('הקראה', { sub: 'פעיל', on: true, toggle: true }) + divider() + row('צליל חזק', { sub: 'כבוי', on: false }) + divider() +
      row('גם מהרמקול עם אוזניות', { sub: 'כבוי', on: false })) +
    add(row('שעות שקט', { sub: 'כבוי', on: false, toggle: true }) + divider() + row('עקיפת נא לא להפריע', { sub: 'פעיל', on: true }) + divider() +
      row('עקיפת מצב שקט', { sub: 'צליל גם כשהטלפון על שקט או רטט', on: true }) + divider() + row('מצב שבת', { sub: 'אוטומטי', on: true, toggle: true })) +
    note('מצב שבת עוקף את שעות השקט, נא לא להפריע ומצב שקט'));
}

function quietPage() {
  return ouiPage('שעות שקט', add(row('שעות שקט', { on: true })) +
    note('בשעות האלה התרעות מקדימות והודעות סיום אירוע מגיעות בשקט. התרעות ירי נשמעות תמיד') +
    add(row('התחלה', { end: '23:00', endBig: true }) + divider() + row('סיום', { end: '07:00', endBig: true })) + note('מצב שבת עוקף את שעות השקט'));
}

function testRow(label, level, ic) {
  return box(box(icon(ic, 18, W, { n: 'אייקון ' + label, st: level !== ALERT ? 'transform:scale(1.4)' : '' }),
      `width:36px;height:36px;border-radius:50%;background:${LEVEL_COLOR[level]};display:flex;align-items:center;justify-content:center;flex:none;margin-inline-end:14px`, { n: 'עיגול ' + label }) +
    t(label, 16, C.TEXT, { st: 'flex:1', n: label }), `display:flex;align-items:center;height:${ROW_H}px;padding:0 18px`, { n: 'שורה: ' + label });
}
function testPage() {
  const hdr = s => t(s, 13, C.MUTED2, { pad: `6px ${PAGE_SIDE + 16}px`, n: 'כותרת: ' + s });
  const sec = rows => add(rows.join(divider()));
  return ouiPage('בדיקת התרעה',
    hdr('1 · מבזק') + sec([testRow('מבזק כללי', PRE, 'ic_list_pre'), testRow('מבזק – איום מלבנון', PRE, 'ic_list_pre')]) +
    hdr('2 · התרעה') + sec([testRow('ירי רקטות וטילים', ALERT, 'ic_alert_rocket'), testRow('חדירת כלי טיס עוין', ALERT, 'ic_alert_aircraft'),
      testRow('חדירת מחבלים', ALERT, 'ic_alert_infiltration'), testRow('רעידת אדמה', ALERT, 'ic_alert_quake'), testRow('צונאמי', ALERT, 'ic_alert_tsunami'),
      testRow('אירוע רדיולוגי', ALERT, 'ic_alert_radio'), testRow('אירוע חומרים מסוכנים', ALERT, 'ic_alert_hazmat')]) +
    hdr('3 · סיום') + sec([testRow('האירוע הסתיים', END, 'ic_list_end'), testRow('עדכון – הנחיות חזרה למבנים', END, 'ic_list_end')]) +
    hdr('4 · אירוע מלא') + add(row('מבזק', { sub: 'מבזק כללי' }) + divider() + row('התרעה', { sub: 'ירי רקטות וטילים' }) + divider() +
      row('סיום', { sub: 'האירוע הסתיים' }) + divider() + row('הפעלת האירוע המלא', { sub: 'מבזק ← התרעה ← סיום' })) +
    note('התרעה 15 שניות אחרי המבזק, וסיום 20 שניות אחרי שנגמר הזמן להגעה למרחב המוגן. ההתרעות נשלחות מיד, בדיוק כמו התרעה אמיתית'));
}

function shabbatPage() {
  return ouiPage('מצב שבת',
    add(row('כבוי', { radio: false }) + divider() + row('דלוק', { radio: false }) + divider() + row('אוטומטי', { sub: 'בשבת ובחג לפי המיקום', radio: true })) +
    note('במצב אוטומטי, מצב שבת נדלק בכניסת השבת ונכבה ביציאתה – בלי לגעת') +
    add(row('שעון במסך', { sub: 'המסך נשאר דלוק עם שעון, תאריך וצאת השבת', on: true }) + divider() + row('שניות', { sub: 'כבוי', on: false })) +
    note('בזמן מצב שבת המסך לא מגיב לנגיעות. יציאה – לחיצה ארוכה של 5 שניות על הכפתור בתחתית') +
    add(t('השבת הקרובה', 16, C.TEXT, { pad: '14px 18px 4px', n: 'כותרת השבת הקרובה' }) + row('מיקום', { sub: 'ירושלים' }) + divider() +
      row('כניסה', { sub: 'יום שישי, 13 בנובמבר', subColor: C.MUTED, end: '16:00', endBig: true }) + divider() +
      row('יציאה', { sub: 'שבת, 14 בנובמבר', subColor: C.MUTED, end: '17:18', endBig: true })) +
    note('לפי המיקום וזמני עתים לבינה: כניסה לפי מנהג המקום (ירושלים 40 דק׳, חיפה 30, תל אביב 22...), יציאה בצאת הכוכבים') +
    add(t('מה מצב שבת עושה', 16, C.TEXT, { pad: '14px 18px 0', n: 'כותרת מה מצב שבת עושה' }) + otext([
      'כל התרעה נפתחת במסך מלא, גם כשהטלפון בשימוש', 'ההתרעה מוקראת בקול, גם אם ההקראה כבויה בהגדרות',
      'בהתרעה המסך נדלק, ואחרי 2 דקות נכבה (או חוזר לשעון) – אין צורך לגעת בטלפון',
      'עוקף הכול: שעות שקט, נא לא להפריע, מצב שקט של הטלפון, וגם צלילים ורטט כבויים'].map(x => '• ' + x).join('\n'))));
}

function permissionsPage() {
  const p = (title, sub) => row(title, { sub, subColor: C.MUTED, end: 'תקין', endColor: C.GREEN });
  return ouiPage('הרשאות', add(p('מסך מלא בהתרעה', 'התרעה נפתחת על כל המסך גם כשהטלפון בשימוש') + divider() + p('חיסכון בסוללה', 'האפליקציה לא נעצרת ברקע') + divider() +
    p('התראות בטלפון', 'התראות האפליקציה מופעלות') + divider() + p('נא לא להפריע', 'מאפשר לעקוף נא לא להפריע בזמן התרעה')) + note('לחיצה על הרשאה פותחת את ההגדרה בטלפון'));
}

function updatesPage() {
  const btn = box(t('בדוק אם יש עדכונים', 16, W, { bold: true, center: true, n: 'כיתוב הכפתור' }),
    'position:sticky;bottom:10px;margin:20px auto 0;width:50%;height:46px;border-radius:23px;background:#4C7DFF;display:flex;align-items:center;justify-content:center', { n: 'כפתור בדיקת עדכונים' });
  return ouiPage('עדכונים',
    add(row('גרסה מותקנת', { end: 'v4.25b', endColor: C.MUTED }) + divider() + row('עודכן לאחרונה', { end: 'היום 16:43', endColor: C.MUTED }) + divider() +
      row('בדיקה אחרונה', { end: 'לפני דקה', endColor: C.MUTED })) + note('מהעדכון השני ההתקנה אוטומטית, בלי ללחוץ על "התקן"') +
    add(row('עדכון אוטומטי', { sub: 'גרסה חדשה מותקנת לבד ברקע', on: true })) + note('לא מתעדכן באמצע אירוע – מחכה שיסתיים') +
    add(row('גרסאות בטא', { sub: 'מקבל גרסאות ניסיון', on: true })) + note('גרסאות לפני שהן משוחררות לכולם') + btn);
}

function aboutPage() {
  const top = box(box(`<img src="../icons/launcher.png" style="width:88px;height:88px">`, 'display:flex;justify-content:center', { n: 'אייקון האפליקציה' }) +
    t('צופר חירום', 24, C.TEXT, { bold: true, center: true, pad: '12px 0 0', n: 'שם האפליקציה' }) +
    t('גרסה 4.25b', 14, C.MUTED, { center: true, pad: '4px 0 0', n: 'גרסה' }) +
    t('התרעות פיקוד העורף בזמן אמת, גם כשהאפליקציה סגורה והמסך כבוי', 13, C.MUTED2, { center: true, pad: '10px 30px 0', n: 'תיאור' }),
    'padding:10px 0 22px', { n: 'ראש העמוד' });
  return ouiPage('אודות', top +
    add(t('הבהרה', 16, C.TEXT, { pad: '14px 18px 0', n: 'כותרת הבהרה' }) + otext('האפליקציה אינה תחליף לאמצעי ההתרעה הרשמיים של פיקוד העורף. ייתכנו עיכובים או תקלות בקבלת ההתרעות.')) +
    add(row('תנאי שימוש') + divider() + row('מקורות', { sub: '8 מקורות' }) + divider() + row('רישיונות', { sub: 'ספריות קוד ונתונים' })) +
    t('נבנתה על ידי טל ששון · © 2026 כל הזכויות שמורות', 12, C.MUTED, { center: true, pad: '10px 20px 0', n: 'זכויות' }));
}

// ===================== שעון ושבת =====================

function nightClock() {
  return `<div style="min-height:100%;background:#000;display:flex;align-items:center;justify-content:center">${t(nowHM(false), 84, '#8A8A8E', { bold: true, center: true, n: 'השעה' })}</div>`;
}
function shabbatClock() {
  const d = new Date();
  return `<div style="position:relative;min-height:100%;background:#000;display:flex;flex-direction:column;align-items:center;justify-content:center">` +
    t(nowHM(false), 96, W, { bold: true, center: true, st: 'line-height:1;letter-spacing:.02em', n: 'השעה' }) +
    t((d.getDay() === 6 ? 'שבת' : 'יום ' + DAYS[d.getDay()]) + ', כ״ב בחשוון תשפ״ז', 22, '#D8D8D8', { center: true, st: 'margin-top:10px', n: 'התאריך' }) +
    t('צאת השבת 17:18', 20, '#B0B0B0', { center: true, st: 'margin-top:28px', n: 'צאת השבת' }) +
    t('מוגן · צליל ורטט פעילים', 17, '#34C759', { bold: true, center: true, pad: '8px 18px', st: 'margin-top:36px;border-radius:22px;background:' + css('#1A34C759'), n: 'מצב ההגנה' }) +
    t('לחיצה ארוכה ליציאה', 15, '#9A9A9A', { center: true, pad: '12px 20px', st: 'position:absolute;bottom:28px;border-radius:24px;background:' + css('#1AFFFFFF'), n: 'כפתור יציאה' }) + '</div>';
}
function shabbatAlert() {
  return `<div style="min-height:100%;background:${LEVEL_COLOR[0]};display:flex;flex-direction:column;align-items:center;justify-content:center;padding:0 24px;box-sizing:border-box">` +
    `<div style="margin-bottom:8px">${icon('ic_alert_rocket', 64, null, { n: 'אייקון ההתרעה' })}</div>` +
    t('ירי רקטות וטילים', 34, W, { bold: true, center: true, n: 'כותרת' }) +
    t('היכנסו למרחב המוגן', 24, W90, { center: true, st: 'margin-top:4px', n: 'הנחיה' }) +
    t(AREA, 28, W, { bold: true, center: true, st: 'margin-top:22px', n: 'האזור' }) +
    t('זמן התגוננות', 18, W90, { center: true, st: 'margin-top:26px', n: 'כיתוב זמן התגוננות' }) +
    t('1:23', 72, W, { bold: true, center: true, st: 'line-height:1', n: 'ספירה לאחור' }) + '</div>';
}

// ===================== הרשימה =====================

const SCREENS = [
  { group: 'המסך הראשי', id: 'main', name: 'מסך ראשי', build: () => mainScreen() },
  { group: 'המסך הראשי', id: 'main-card-red', name: 'כרטיס התרעה – ירי', build: () => mainScreen({ alert: ALERT }) },
  { group: 'המסך הראשי', id: 'main-card-pre', name: 'כרטיס התרעה – מבזק', build: () => mainScreen({ alert: PRE }) },
  { group: 'המסך הראשי', id: 'main-card-end', name: 'כרטיס התרעה – סיום', build: () => mainScreen({ alert: END }) },
  { group: 'המסך הראשי', id: 'main-mini-red', name: 'ממוזער במסך הראשי – ירי', build: () => mainScreen({ mini: ALERT }) },
  { group: 'מסכי התרעה', id: 'full-red', name: 'מסך מלא – ירי', bar: LEVEL_COLOR[0], build: () => alertScreen(ALERT, false) },
  { group: 'מסכי התרעה', id: 'full-pre', name: 'מסך מלא – מבזק', bar: LEVEL_COLOR[1], build: () => alertScreen(PRE, false) },
  { group: 'מסכי התרעה', id: 'full-end', name: 'מסך מלא – סיום', bar: LEVEL_COLOR[2], build: () => alertScreen(END, false) },
  { group: 'מסכי התרעה', id: 'popup-red', name: 'פופ-אפ – ירי', build: () => alertScreen(ALERT, true) },
  { group: 'מסכי התרעה', id: 'popup-pre', name: 'פופ-אפ – מבזק', build: () => alertScreen(PRE, true) },
  { group: 'מסכי התרעה', id: 'popup-end', name: 'פופ-אפ – סיום', build: () => alertScreen(END, true) },
  { group: 'מסכי התרעה', id: 'mini-red', name: 'ממוזער – ירי', build: () => miniOver(ALERT) },
  { group: 'מסכי התרעה', id: 'mini-pre', name: 'ממוזער – מבזק', build: () => miniOver(PRE) },
  { group: 'מסכי התרעה', id: 'mini-end', name: 'ממוזער – סיום', build: () => miniOver(END) },
  { group: 'התרעות אחרונות', id: 'recent', name: 'התרעות אחרונות', build: recentPage },
  { group: 'התרעות אחרונות', id: 'detail-red', name: 'פרטי התרעה – ירי', bar: LEVEL_COLOR[0], build: () => alertDetail(ALERT) },
  { group: 'התרעות אחרונות', id: 'detail-pre', name: 'פרטי התרעה – מבזק', bar: LEVEL_COLOR[1], build: () => alertDetail(PRE) },
  { group: 'התרעות אחרונות', id: 'detail-end', name: 'פרטי התרעה – סיום', bar: LEVEL_COLOR[2], build: () => alertDetail(END) },
  { group: 'הגדרות', id: 'settings', name: 'הגדרות', build: settingsMain },
  { group: 'הגדרות', id: 'protection', name: 'הגנה', build: protectionPage },
  { group: 'הגדרות', id: 'display', name: 'תצוגה', build: displayPage },
  { group: 'הגדרות', id: 'clock-settings', name: 'תצוגה › שעון', build: clockPage },
  { group: 'הגדרות', id: 'alerts', name: 'התרעות', build: alertSettingsPage },
  { group: 'הגדרות', id: 'style', name: 'התרעות › סגנון התרעה', build: stylePage },
  { group: 'הגדרות', id: 'sound', name: 'צלילים ורטט', build: soundPage },
  { group: 'הגדרות', id: 'quiet', name: 'צלילים ורטט › שעות שקט', build: quietPage },
  { group: 'הגדרות', id: 'test', name: 'בדיקת התרעה', build: testPage },
  { group: 'הגדרות', id: 'shabbat', name: 'מצב שבת', build: shabbatPage },
  { group: 'הגדרות', id: 'permissions', name: 'הרשאות', build: permissionsPage },
  { group: 'הגדרות', id: 'updates', name: 'עדכונים', build: updatesPage },
  { group: 'הגדרות', id: 'about', name: 'אודות', build: aboutPage },
  { group: 'שעון ושבת', id: 'night', name: 'שעון לילה', bar: '#000000', build: nightClock },
  { group: 'שעון ושבת', id: 'shabbat-clock', name: 'מסך שבת – שעון', bar: '#000000', build: shabbatClock },
  { group: 'שעון ושבת', id: 'shabbat-alert', name: 'מסך שבת – התרעה', bar: LEVEL_COLOR[0], build: shabbatAlert },
];
