#!/bin/bash
# שרת עזר ב-Oracle (tzofar-server, 84.13.68.101): בודק את פיקוד העורף ומעביר לשרת הראשי דרך /relay,
# בדיוק כמו "ממסר צופר" בבית. מתקינים פעם אחת מהמחשב:
#   ssh -i <קובץ המפתח> ubuntu@<כתובת> "curl -fsSL https://raw.githubusercontent.com/t0548321068/red-alert/main/tools/oref-node.sh | sudo bash -s <מפתח הממסר>"
# אם פיקוד העורף חוסם גם את השרת הזה - רק מדווח ולא מתקין כלום.
set -e
KEY="$1"
if [ -z "$KEY" ]; then echo "missing relay key"; exit 1; fi
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'
IP=$(curl -s -m 10 https://api.ipify.org || echo '?')
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 15 -A "$UA" -H 'Referer: https://www.oref.org.il/' -H 'X-Requested-With: XMLHttpRequest' \
  "https://www.oref.org.il/warningMessages/alert/Alerts.json?t=$(date +%s)" || echo 000)
echo "=============================================="
echo " server IP: $IP"
echo " Pikud HaOref answer: HTTP $CODE"
if [ "$CODE" != "200" ]; then
  echo " RESULT: BLOCKED - nothing installed"
  echo "=============================================="
  exit 0
fi
echo " RESULT: WORKS - installing the relay service"
echo "=============================================="

install -m 600 /dev/null /etc/tzofar-relay.key
echo "$KEY" > /etc/tzofar-relay.key

cat > /usr/local/bin/tzofar-oref-node <<'EOF'
#!/bin/bash
# כל 3 שניות הקובץ החי, כל 10 שניות ההיסטוריה -> השרת הראשי (/relay)
KEY=$(cat /etc/tzofar-relay.key)
S=https://tzofar-app.duckdns.org/relay
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'
T=$(mktemp -d)
fetch() {   # $1 url, $2 file -> קוד HTTP; UTF-16 מומר ל-UTF-8 (כמו בממסר)
  local c
  c=$(curl -s -o "$2" -w '%{http_code}' -m 8 -A "$UA" -H 'Accept: application/json, text/plain, */*' \
    -H 'Accept-Language: he-IL,he;q=0.9' -H 'Referer: https://www.oref.org.il/' -H 'X-Requested-With: XMLHttpRequest' "$1" || echo 000)
  if [ "$c" = 200 ] && [ "$(head -c2 "$2" | od -An -tx1 | tr -d ' ')" = "fffe" ]; then
    iconv -f UTF-16 -t UTF-8 "$2" > "$2.8" && mv "$2.8" "$2"
  fi
  echo "$c"
}
send() { curl -s -m 8 -o /dev/null -X POST -H "x-relay-key: $KEY" --data-binary @"$2" "$S/$1" || true; }
lastH=0; lastBad=0
while true; do
  now=$(date +%s)
  c=$(fetch "https://www.oref.org.il/warningMessages/alert/Alerts.json?t=$now" "$T/live")
  if [ "$c" = 200 ]; then send live "$T/live"
  elif [ $((now - lastBad)) -ge 300 ]; then echo "oref live HTTP $c"; lastBad=$now; fi
  if [ $((now - lastH)) -ge 10 ]; then
    lastH=$now
    [ "$(fetch https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json "$T/hist")" = 200 ] && send history "$T/hist"
  fi
  sleep 3
done
EOF
chmod 755 /usr/local/bin/tzofar-oref-node

cat > /etc/systemd/system/tzofar-oref-node.service <<'EOF'
[Unit]
Description=tzofar: Pikud HaOref -> main server (/relay)
After=network-online.target
Wants=network-online.target
[Service]
ExecStart=/usr/local/bin/tzofar-oref-node
Restart=always
RestartSec=5
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now tzofar-oref-node
sleep 4
systemctl is-active tzofar-oref-node && echo "service running - done"
