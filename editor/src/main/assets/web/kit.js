// ערכת בנייה: אותם רכיבים כמו ב-UiKit.kt של צופר חירום (מידות ב-dp = px כאן, גופן ב-sp = px כאן).
// כל רכיב שאפשר לערוך מקבל data-e (מזהה), data-n (שם בעברית) ו-data-k (t טקסט · b מסגרת/רקע · i אייקון)
'use strict';

// ---- צבעים: בדיוק MainActivity.C ----
const C = {};
function applyTheme(dark) {
  const c = (d, l) => dark ? d : l;
  Object.assign(C, {
    BG: c('#000000', '#F2F2F7'), CHIP: c('#2C2C2E', '#E5E5EA'), TEXT: c('#F2F2F2', '#1C1C1E'),
    MUTED: c('#8E8E93', '#6E6E73'), GREEN: c('#30D158', '#248A3D'), OFF: c('#636366', '#8E8E93'),
    BLUE: c('#0A84FF', '#007AFF'), RED: c('#FF3B30', '#E0352B'), ORANGE: c('#FF9F0A', '#E08600'),
    CARD2: c('#1B1B1D', '#FFFFFF'), DIV: c('#2A2A2C', '#E0E0E5'), SEPV: c('#5C5C5E', '#C7C7CC'),
    SOFT: c('#E6FFFFFF', '#CC1C1C1E'), MUTED2: c('#AAAAAA', '#6E6E73'), LINE2: c('#22FFFFFF', '#22000000'),
    LIGHT: !dark, OUI_BLUE: '#5AA9FF'
  });
}
applyTheme(true);

/** צבע אנדרואיד (#AARRGGBB / #RRGGBB) ל-CSS */
function css(col) {
  if (!col || col[0] !== '#') return col;
  if (col.length === 9) {
    const a = parseInt(col.slice(1, 3), 16) / 255;
    return `rgba(${parseInt(col.slice(3, 5), 16)},${parseInt(col.slice(5, 7), 16)},${parseInt(col.slice(7, 9), 16)},${+a.toFixed(3)})`;
  }
  return col;
}
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

// ---- מזהים לרכיבים: לפי המסך, ובתוך המסך לפי השם ----
let SCR = '';            // המסך שנבנה עכשיו
const used = {};
function key(base) {
  const k = base.replace(/\s+/g, ' ').trim().slice(0, 60) || 'x';
  const n = (used[k] = (used[k] || 0) + 1);
  return n === 1 ? k : k + '#' + n;
}
function resetKeys() { for (const k in used) delete used[k]; }
function attrs(o, kind, fallback) {
  const id = key(o.id || fallback);
  return ` data-e="${esc(id)}" data-n="${esc(o.n || fallback)}" data-k="${kind}"`;
}

// ---- טקסט ----
/**
 * t(טקסט, גודל, צבע, {bold, center, pad:'t r b l', st:'css נוסף', id, n, lines})
 * pad - כמו setPadding: בסדר CSS (למעלה ימין למטה שמאל)
 */
function t(s, size, color, o = {}) {
  let st = `font-size:${size}px;color:${css(color)};`;
  if (o.bold) st += 'font-weight:bold;';
  if (o.center) st += 'text-align:center;';
  if (o.pad) st += `padding:${o.pad};`;
  if (o.lines) st += `display:-webkit-box;-webkit-line-clamp:${o.lines};-webkit-box-orient:vertical;overflow:hidden;`;
  if (o.st) st += o.st;
  return `<div class="tv"${attrs(o, 't', o.n || String(s).split('\n')[0])} style="${st}">${esc(s).replace(/\n/g, '<br>')}</div>`;
}

/** מסגרת: box(תוכן, css, {id, n}) */
function box(inner, st, o = {}) {
  return `<div${o.n || o.id ? attrs(o, 'b', o.n || o.id) : ''} style="${st}">${inner}</div>`;
}

// ---- אייקונים: ממירים את ה-Vector Drawable של האפליקציה (assets/icons) ל-SVG ----
const iconCache = {};
function androidColor(v) {
  if (!v || v[0] !== '#') return v && v[0] === '@' ? '#FFFFFF' : null;
  if (v.length === 9 && v.slice(1, 3).toUpperCase() === '00') return 'none';
  return css(v);
}
function vectorToSvg(xml) {
  const doc = new DOMParser().parseFromString(xml, 'text/xml');
  const v = doc.documentElement;
  const A = (n, a) => n.getAttribute('android:' + a);
  const vw = A(v, 'viewportWidth') || 24, vh = A(v, 'viewportHeight') || 24;
  function walk(n) {
    let out = '';
    for (const ch of Array.from(n.children)) {
      if (ch.tagName === 'group') {
        const px = +(A(ch, 'pivotX') || 0), py = +(A(ch, 'pivotY') || 0);
        const tx = +(A(ch, 'translateX') || 0), ty = +(A(ch, 'translateY') || 0);
        const sx = +(A(ch, 'scaleX') || 1), sy = +(A(ch, 'scaleY') || 1), r = +(A(ch, 'rotation') || 0);
        out += `<g transform="translate(${tx + px},${ty + py}) rotate(${r}) scale(${sx},${sy}) translate(${-px},${-py})">${walk(ch)}</g>`;
      } else if (ch.tagName === 'path') {
        const fill = androidColor(A(ch, 'fillColor')) || 'none';
        const stroke = androidColor(A(ch, 'strokeColor')) || 'none';
        let a = `d="${A(ch, 'pathData')}" fill="${fill}" stroke="${stroke}"`;
        if (fill !== 'none') a += ' class="vf"';
        if (stroke !== 'none') a += ` stroke-width="${A(ch, 'strokeWidth') || 1}" class="${fill !== 'none' ? 'vf vs' : 'vs'}"`;
        if (A(ch, 'fillType') === 'evenOdd') a += ' fill-rule="evenodd"';
        if (A(ch, 'strokeLineCap')) a += ` stroke-linecap="${A(ch, 'strokeLineCap')}"`;
        if (A(ch, 'strokeLineJoin')) a += ` stroke-linejoin="${A(ch, 'strokeLineJoin')}"`;
        if (A(ch, 'fillAlpha')) a += ` fill-opacity="${A(ch, 'fillAlpha')}"`;
        if (A(ch, 'strokeAlpha')) a += ` stroke-opacity="${A(ch, 'strokeAlpha')}"`;
        out += `<path ${a}/>`;
      }
    }
    return out;
  }
  return `<svg viewBox="0 0 ${vw} ${vh}" width="100%" height="100%">${walk(v)}</svg>`;
}
function svgOf(name) {
  if (!(name in iconCache)) {
    const xml = window.Native ? Native.asset('icons/' + name + '.xml') : '';
    iconCache[name] = xml ? vectorToSvg(xml) : '';
  }
  return iconCache[name];
}
/** אייקון: icon(שם, גודל, צבע-גוון או null = הצבעים המקוריים, {id, n, st}) */
function icon(name, size, tint, o = {}) {
  let svg = svgOf(name);
  const tintCss = tint ? `--tint:${css(tint)};` : '';
  return `<div class="ic${tint ? ' tinted' : ''}"${o.n || o.id ? attrs(o, 'i', o.n || o.id) : ''} style="width:${size}px;height:${size}px;${tintCss}${o.st || ''}">${svg}</div>`;
}

// ---- One UI: כמו ouiCard / ouiRow / ouiDivider / ouiNote ----
const PAGE_SIDE = 16, ROW_H = 58;

/** כותרת עמוד (pageHeader): 24 מודגש, גובה 68, ואייקונים בסוף השורה */
function pageHeader(title, icons = '') {
  return box(t(title, 24, C.TEXT, { bold: true, pad: '0 4px', id: 'כותרת העמוד', n: 'כותרת העמוד', st: 'flex:1' }) + icons,
    `display:flex;align-items:center;min-height:68px;box-sizing:border-box;padding:10px 16px 12px 16px;`);
}
/** אייקון בראש העמוד (46, ריפוד 11) */
function headerIcon(name, n) {
  return box(icon(name, 24, C.TEXT, { n: 'אייקון ' + n }), 'width:46px;height:46px;padding:11px;box-sizing:border-box;flex:none', { n: 'כפתור ' + n });
}
/** כרטיס (graphite: תמיד פינות 28) */
function card(inner, o = {}) {
  return box(inner, `background:${css(C.CARD2)};border-radius:28px;overflow:hidden;${o.st || ''}`, { n: o.n || 'כרטיס' });
}
/** ouiAdd: כרטיס בעמוד עם שוליים 16 ו-12 מתחת */
function add(inner, o = {}) {
  return `<div style="margin:0 ${PAGE_SIDE}px 12px">${card(inner, o)}</div>`;
}
function divider() { return box('', `height:1px;background:${css(C.DIV)};margin:0 18px`, { n: 'קו מפריד' }); }
function note(s) { return t(s, 11, C.MUTED2, { pad: `0 ${PAGE_SIDE + 16}px 14px`, n: 'הסבר: ' + s.slice(0, 30) }); }
/** ouiText: טקסט חופשי בכרטיס */
function otext(s) { return t(s, 14, C.TEXT, { pad: '12px 18px', st: 'line-height:1.35', n: 'טקסט: ' + s.slice(0, 30) }); }

/** מתג One UI: 30x18, דלוק = כחול והעיגול משמאל */
function sw(on) {
  const tr = on ? '#4878F6' : (C.LIGHT ? '#BDBDC2' : '#646368');
  return box(`<div style="position:absolute;top:1.6px;${on ? 'left' : 'right'}:1.6px;width:14.8px;height:14.8px;border-radius:50%;background:#fff"></div>`,
    `position:relative;width:30px;height:18px;border-radius:9px;background:${tr};flex:none`, { n: 'מתג' });
}
/** כפתור בחירה (ouiRadio) */
function radio(sel) {
  return box(sel ? '<div style="width:10px;height:10px;border-radius:50%;background:#3E82F7"></div>' : '',
    `width:20px;height:20px;box-sizing:border-box;border-radius:50%;border:2px solid ${sel ? '#3E82F7' : '#6E6E73'};display:flex;align-items:center;justify-content:center;flex:none`,
    { n: 'כפתור בחירה' });
}
/**
 * row(שם, {sub, subColor, end, endColor, endBig, radio, on, toggle}) - שורה בגובה 58 (ouiRow).
 * toggle=true: קו מפריד ומתג בצד (switchZone)
 */
function row(title, o = {}) {
  let inner = '<div style="flex:1;min-width:0">' + t(title, 16, C.TEXT, { n: title, st: 'line-height:1.15' });
  if (o.sub) inner += t(o.sub, 12, o.subColor || C.OUI_BLUE, { pad: '4px 0 0', n: title + ' – שורת מצב', st: 'line-height:1.15' });
  inner += '</div>';
  if (o.end != null) inner += t(o.end, o.endBig ? 22 : 14, o.endColor || C.TEXT, { bold: o.endBig, st: 'margin-inline-start:10px;line-height:1.15', n: title + ' – ערך' });
  if (o.radio != null) inner += `<div style="margin-inline-start:10px">${radio(o.radio)}</div>`;
  if (o.on != null && o.toggle) inner += `<div style="display:flex;align-items:center;align-self:stretch;padding-inline-start:12px"><div style="width:1px;height:20px;background:${css(C.SEPV)};margin-inline-end:12px"></div>${sw(o.on)}</div>`;
  else if (o.on != null) inner += `<div style="margin-inline-start:10px">${sw(o.on)}</div>`;
  return box(inner, `display:flex;align-items:center;height:${ROW_H}px;padding:0 18px`, { n: 'שורה: ' + title });
}
/** כיתוב + כפתור בחירה מתחת לתצוגה מקדימה (addLabelRadio) */
function labelRadio(label, sel) {
  return t(label, 13, sel ? '#5AA9FF' : C.MUTED2, { bold: sel, center: true, pad: '8px 0', n: label }) +
    `<div style="display:flex;justify-content:center">${radio(sel)}</div>`;
}

/** עמוד הגדרות (ouiPage): כותרת ותוכן, 30 מתחת */
function ouiPage(title, inner, icons) {
  return `<div style="background:${css(C.BG)};min-height:100%;padding-bottom:30px">${pageHeader(title, icons)}${inner}</div>`;
}

// ---- זמן לדוגמה ----
function pad2(n) { return String(n).padStart(2, '0'); }
function nowHM(sec) { const d = new Date(); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + (sec ? ':' + pad2(d.getSeconds()) : ''); }
function greeting() {
  const h = new Date().getHours();
  return h >= 5 && h < 12 ? 'בוקר טוב' : h < 16 ? 'צהריים טובים' : h < 19 ? 'אחר צהריים טובים' : h < 22 ? 'ערב טוב' : 'לילה טוב';
}
const DAYS = ['ראשון', 'שני', 'שלישי', 'רביעי', 'חמישי', 'שישי', 'שבת'];
const MONTHS = ['בינואר', 'בפברואר', 'במרץ', 'באפריל', 'במאי', 'ביוני', 'ביולי', 'באוגוסט', 'בספטמבר', 'באוקטובר', 'בנובמבר', 'בדצמבר'];
function dayLine() { return 'יום ' + DAYS[new Date().getDay()] + ' · ' + dateDMY(); }
function dateDMY() { const d = new Date(); return pad2(d.getDate()) + '/' + pad2(d.getMonth() + 1) + '/' + d.getFullYear(); }
