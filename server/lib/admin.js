// שליטה בשרת מהאתר (רק לך): יומן, הגרסה שרצה, העדכון העצמי האחרון, והפעלה מחדש
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFile } = require('child_process');
const { log, logLines } = require('./util');

const REPO = path.join(__dirname, '..', '..');
const UPD_LOG = path.join(os.homedir(), 'selfupdate.log');
const BAD = path.join(os.homedir(), 'bad_commit');

// הגרסה שרצה - נקראת פעם אחת בעלייה (אחרי עדכון השרת עולה מחדש בכל מקרה)
let commit = { hash: '', msg: '', at: 0 };
execFile('git', ['-C', REPO, 'log', '-1', '--format=%h%x09%s%x09%ct'], (e, out) => {
  if (e) return;
  const [hash, msg, at] = out.trim().split('\t');
  commit = { hash, msg, at: +at * 1000 };
});

// סקריפט העדכון העצמי רץ מעותק בבית (~/selfupdate.sh) - מעדכנים את העותק כשהגרסה בגיט שונה (רק אם התחביר תקין)
try {
  const src = path.join(REPO, 'server', 'deploy', 'selfupdate.sh'), dst = path.join(os.homedir(), 'selfupdate.sh');
  if (fs.existsSync(dst) && fs.readFileSync(src, 'utf8') !== fs.readFileSync(dst, 'utf8')) {
    execFile('bash', ['-n', src], e => {
      if (e) return log('selfupdate.sh not installed: syntax error');
      try { fs.writeFileSync(dst + '.tmp', fs.readFileSync(src), { mode: 0o755 }); fs.renameSync(dst + '.tmp', dst); log('selfupdate.sh updated'); }
      catch (err) { log('selfupdate.sh install failed', err.message); }
    });
  }
} catch (_) {}

function tail(file, n) {
  try { return fs.readFileSync(file, 'utf8').trim().split('\n').slice(-n); } catch (_) { return []; }
}

function info(started) {
  const upd = tail(UPD_LOG, 20).reverse();
  let bad = ''; try { bad = fs.readFileSync(BAD, 'utf8').trim().slice(0, 12); } catch (_) {}
  return {
    commit, started, now: Date.now(),
    mem: Math.round(process.memoryUsage().rss / 1048576),
    load: os.loadavg()[0].toFixed(2), node: process.version,
    updates: upd, bad,
    log: logLines.slice(-300).reverse(),
  };
}

let restarting = false;
function restart(res) {
  res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"ok":true}');
  if (restarting) return;
  restarting = true;
  log('restart requested from web');
  // systemd מעלה את השרת מחדש לבד (אותו מנגנון כמו בנפילה)
  // שומרים מיד מה שעוד מחכה לשמירה; נטענים כאן מאוחר (push → web → admin - טעינה מעגלית)
  setTimeout(() => { require('./push').saveNow(); require('./devices').saveNow(); process.exit(1); }, 800);
}

module.exports = { info, restart };
