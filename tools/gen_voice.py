"""
יוצר את הקלטות ההקראה (קול "אבר" של Microsoft) לתוך app/src/main/assets/voice.
רץ בגיטהאב (שם יש גישה לשירות). שם קובץ לאזור = 12 תווים ראשונים של sha1 של השם.
"""
import asyncio, hashlib, json, os, subprocess, sys
import edge_tts

VOICE = "he-IL-AvriNeural"
RATE, PITCH = "+12%", "+8Hz"   # "אבר 2" - קצת יותר מהר וגבוה
# תיקוני הגייה: שם רשמי -> איך להקריא (כתיב מלא / ניקוד)
FIX = json.load(open("tools/pronounce.json", encoding="utf-8")) if os.path.exists("tools/pronounce.json") else {}
OUT = "app/src/main/assets/voice"
os.makedirs(OUT, exist_ok=True)

# שמות הקבצים באנגלית (כדי שיהיה קל למצוא קובץ): tools/voice_names.json
NAMES = json.load(open("tools/voice_names.json", encoding="utf-8"))

import re
def tts_text(t):
    # דגש רק איפה שהוא משנה את ההגייה (בּ כּ פּ, שורוק וּ, מפיק הּ). בשאר האותיות הקול נתקע ממנו ("אשדווד")
    return re.sub(r'([\u05D0-\u05EA])([\u0591-\u05C7]*)',
                  lambda m: m.group(1) + (m.group(2) if m.group(1) in 'בכךפףוה' else m.group(2).replace('\u05BC', '')), t)
items = {}
# אזורים: voice/areas/<english>.mp3
for n, path in NAMES["areas"].items():
    items[path[:-4]] = tts_text(FIX.get(n, n.replace(" - ", " ").replace("-", " ")))
TEXT = {
    "rockets": "ירי רקטות וטילים.", "hostile_aircraft": "חדירת כלי טיס עוין.",
    "terrorist_infiltration": "חדירת מחבלים.", "earthquake": "רעידת אדמה.", "tsunami": "צונאמי.",
    "hazardous_materials": "אירוע חומרים מסוכנים.", "radiological": "אירוע רדיולוגי.",
    "nonconventional": "ירי בלתי קונבנציונלי.", "red_alert": "צבע אדום.", "event_ended": "האירוע הסתיים.",
    "early_warning": "התראה מקדימה. בדקות הקרובות צפויות התרעות.", "more_areas": "ואזורים נוספים.",
    "time_immediate": "זמן להגעה למרחב המוגן: מיידי.", "time_15s": "זמן להגעה למרחב המוגן: 15 שניות.",
    "time_30s": "זמן להגעה למרחב המוגן: 30 שניות.", "time_45s": "זמן להגעה למרחב המוגן: 45 שניות.",
    "time_1min": "זמן להגעה למרחב המוגן: דקה.", "time_1_5min": "זמן להגעה למרחב המוגן: דקה וחצי.",
}
for k2, t in TEXT.items(): items["phrases/" + k2] = t
# שמות ערים (כללי, בלי רובעים) - עוד לא בשימוש. voice/cities/<english>.mp3 + index.json
if os.path.exists("tools/city_names.json"):
    CITIES = json.load(open("tools/city_names.json", encoding="utf-8"))
    os.makedirs(OUT + "/cities", exist_ok=True)
    for heb, c in CITIES.items(): items["cities/" + c["file"]] = tts_text(c["text"])
    json.dump({h: "cities/" + c["file"] + ".mp3" for h, c in CITIES.items()},
              open(OUT + "/cities/index.json", "w", encoding="utf-8"), ensure_ascii=False, indent=0)
# תיקייה נוספת - הקלטות שעוד לא בשימוש באפליקציה (tools/extra_phrases.json)
if os.path.exists("tools/extra_phrases.json"):
    for k2, t in json.load(open("tools/extra_phrases.json", encoding="utf-8")).items():
        items["extra/" + k2] = tts_text(t)
os.makedirs(OUT + "/areas", exist_ok=True); os.makedirs(OUT + "/phrases", exist_ok=True); os.makedirs(OUT + "/extra", exist_ok=True)
json.dump(NAMES["areas"], open(OUT + "/index.json", "w", encoding="utf-8"), ensure_ascii=False, indent=0)

sem = asyncio.Semaphore(6)
async def one(name, text):
    dst = f"{OUT}/{name}.mp3"
    if os.path.exists(dst): return
    tmp = "/tmp/" + name.replace("/", "_") + ".mp3"
    async with sem:
        for attempt in range(5):
            try:
                await edge_tts.Communicate(text, VOICE, rate=RATE, pitch=PITCH).save(tmp)
                break
            except Exception as e:
                last = e
                await asyncio.sleep(2 + attempt * 3)
        else:
            print(f"::warning::FAILED {name}: {last}"); return
    # מונו, 24kHz, 32kbps - קטן ועדיין ברור
    # צליל עמוק יותר, בלי שקט בהתחלה ובסוף
    af = ("treble=g=-5:f=3000,silenceremove=start_periods=1:start_threshold=-45dB,areverse,"
          "silenceremove=start_periods=1:start_threshold=-45dB,areverse,apad=pad_dur=0.12")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp, "-af", af, "-ac", "1", "-ar", "22050", "-b:a", "32k", dst], check=True)

async def safe(n, t):
    try: await one(n, t)
    except Exception as e: print(f"::warning::{n}: {type(e).__name__}: {e}")

async def main():
    await asyncio.gather(*(safe(n, t) for n, t in items.items()))
    done = sum(len(os.listdir(f"{OUT}/{d}")) for d in ("areas", "phrases", "extra", "cities") if os.path.isdir(f"{OUT}/{d}")) - (1 if os.path.exists(f"{OUT}/cities/index.json") else 0)
    print(f"::notice::clips {done} of {len(items)}")
    if done < len(items) * 0.9: sys.exit(f"::error::only {done} clips")
try:
    asyncio.run(main())
except SystemExit: raise
except Exception as e:
    print(f"::error::{type(e).__name__}: {e}"); raise
