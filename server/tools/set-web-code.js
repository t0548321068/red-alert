// הגדרת / החלפת קוד הכניסה לאתר. הקוד לא נשמר - רק טביעה שלו (scrypt) בקובץ web_auth.json.
// הרצה בשרת: node tools/set-web-code.js   (מקלידים את הקוד פעמיים - הוא לא מוצג על המסך)
// החלפת קוד מנתקת את כל מי שמחובר.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

function ask(q) {
  return new Promise(resolve => {
    process.stdout.write(q);
    const stdin = process.stdin;
    let s = '';
    stdin.setRawMode && stdin.setRawMode(true);
    stdin.resume(); stdin.setEncoding('utf8');
    const on = ch => {
      if (ch === '\r' || ch === '\n') { stdin.removeListener('data', on); stdin.setRawMode && stdin.setRawMode(false); stdin.pause(); process.stdout.write('\n'); resolve(s); }
      else if (ch === '\u0003') process.exit(1);
      else if (ch === '\u007f') s = s.slice(0, -1);
      else s += ch;
    };
    stdin.on('data', on);
  });
}

(async () => {
  const a = await ask('קוד כניסה חדש (לפחות 8 תווים): ');
  if (a.length < 8) { console.log('קצר מדי - לא נשמר'); process.exit(1); }
  const b = await ask('שוב, לאישור: ');
  if (a !== b) { console.log('לא תואם - לא נשמר'); process.exit(1); }
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(a, salt, 32);
  const file = path.join(__dirname, '..', 'web_auth.json');
  fs.writeFileSync(file, JSON.stringify({ salt: salt.toString('hex'), hash: hash.toString('hex'), secret: crypto.randomBytes(32).toString('hex') }), { mode: 0o600 });
  console.log('נשמר. אפשר להיכנס לאתר עם הקוד החדש.');
})();
