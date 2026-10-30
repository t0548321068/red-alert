# התקנת השרת (Oracle)

- `tzofar.service` – השרת (`node server/index.js`), עולה מחדש לבד אם נופל.
- `tzofar-update.timer` – כל 5 דקות מריץ את `selfupdate.sh` (עותק ב-/home/ubuntu/selfupdate.sh).
- `selfupdate.sh` – מושך את ענף `server`, בודק תקינות, ואם השרת לא עונה אחרי ההפעלה – חוזר לגרסה הקודמת.
- יומן העדכונים: /home/ubuntu/selfupdate.log
