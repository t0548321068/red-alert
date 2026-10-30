#!/bin/bash
# עדכון עצמי של השרת (רץ כל 5 דקות מ-tzofar-update.timer):
# מושך את ענף server מ-GitHub, בודק שהקוד תקין, מפעיל מחדש - ואם השרת לא עונה, חוזר לגרסה הקודמת.
cd /home/ubuntu/red-alert || exit 0
LOG=/home/ubuntu/selfupdate.log
git fetch -q --depth 1 origin server || exit 0
PREV=$(git rev-parse HEAD); NEW=$(git rev-parse FETCH_HEAD)
[ "$PREV" = "$NEW" ] && exit 0
# גרסה שכבר נכשלה - לא מנסים שוב עד שתגיע גרסה אחרת
[ "$(cat /home/ubuntu/bad_commit 2>/dev/null)" = "$NEW" ] && exit 0
PKG_OLD=$(git rev-parse HEAD:server/package.json 2>/dev/null)
git checkout -q -B live "$NEW"
deps() { [ "$PKG_OLD" != "$(git rev-parse HEAD:server/package.json)" ] && (cd server && npm install --omit=dev --no-audit --no-fund -q); }
back() {
  echo "$NEW" > /home/ubuntu/bad_commit
  git checkout -q -B live "$PREV"; deps; sudo systemctl restart tzofar
  echo "$(date -Is) FAILED ($1) $(git log --oneline -1 "$NEW" 2>/dev/null | head -c 80) - rolled back to $(git log --oneline -1)" >> $LOG
  exit 1
}
node --check server/index.js || back "syntax"
deps
sudo systemctl restart tzofar
sleep 15
systemctl is-active -q tzofar || back "not running"
curl -sf -m 5 localhost:8080/status > /dev/null || back "no answer"
echo "$(date -Is) updated to $(git log --oneline -1 | head -c 100)" >> $LOG
