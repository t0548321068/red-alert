// גרסאות חדשות: השרת בודק את GitHub כל 2 דקות, ושולח לטלפונים הודעה רק כשיש שינוי
// (במקום שכל טלפון יתעורר לבדוק בעצמו)
const { get, head, log } = require('./util');
const { updatePush } = require('./push');

const REPO = 'https://github.com/t0548321068/red-alert/releases';
const latest = { stable: '', beta: '' };

async function poll() {
  try {
    const loc = await head(REPO + '/latest');
    const stable = (loc.split('/tag/')[1] || '').replace(/^v/, '');
    let beta = '';
    try { const m = /red-alert-([\w.]+)-(\d+)\.apk/.exec(await get(REPO + '/expanded_assets/beta')); if (m) beta = m[1] + '-' + m[2]; } catch (_) {}
    if (!stable && !beta) return;
    const changed = (stable && stable !== latest.stable) || (beta && beta !== latest.beta);
    const first = !latest.stable && !latest.beta;
    if (stable) latest.stable = stable;
    if (beta) latest.beta = beta;
    if (changed && !first) { await updatePush(latest); log('new version', JSON.stringify(latest)); }
  } catch (_) {}
}

function start() { setInterval(poll, 2 * 60 * 1000); setTimeout(poll, 5000); }

module.exports = { start, latest };
