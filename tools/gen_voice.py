"""
יוצר את הקלטות ההקראה (קול "אבר" של Microsoft) לתוך app/src/main/assets/voice.
רץ בגיטהאב (שם יש גישה לשירות). שם קובץ לאזור = 12 תווים ראשונים של sha1 של השם.
"""
import asyncio, hashlib, json, os, subprocess, sys
import edge_tts

VOICE = "he-IL-AvriNeural"
OUT = "app/src/main/assets/voice"
os.makedirs(OUT, exist_ok=True)

def key(s): return hashlib.sha1(s.encode("utf-8")).hexdigest()[:12]

areas = json.loads(open("app/src/main/assets/areas.js", encoding="utf-8").read()
                   .strip().removeprefix("var ALL_AREAS=").removesuffix(";"))
items = {}
for n in areas:
    items["a_" + key(n)] = n.replace(" - ", " ").replace("-", " ")
items["a_" + key("התראת בדיקה")] = "התראת בדיקה"
PHRASES = {
    "t_rockets": "ירי רקטות וטילים.",
    "t_uav": "חדירת כלי טיס עוין.",
    "t_terror": "חדירת מחבלים.",
    "t_quake": "רעידת אדמה.",
    "t_tsunami": "צונאמי.",
    "t_hazmat": "אירוע חומרים מסוכנים.",
    "t_radio": "אירוע רדיולוגי.",
    "t_nonconv": "ירי בלתי קונבנציונלי.",
    "t_generic": "צבע אדום.",
    "t_end": "האירוע הסתיים.",
    "t_pre": "התראה מקדימה. בדקות הקרובות צפויות התרעות.",
    "more": "ואזורים נוספים.",
    "s_0": "זמן להגעה למרחב המוגן: מיידי.",
    "s_15": "זמן להגעה למרחב המוגן: 15 שניות.",
    "s_30": "זמן להגעה למרחב המוגן: 30 שניות.",
    "s_45": "זמן להגעה למרחב המוגן: 45 שניות.",
    "s_60": "זמן להגעה למרחב המוגן: דקה.",
    "s_90": "זמן להגעה למרחב המוגן: דקה וחצי.",
}
items.update(PHRASES)

sem = asyncio.Semaphore(6)
async def one(name, text):
    dst = f"{OUT}/{name}.mp3"
    if os.path.exists(dst): return
    tmp = f"/tmp/{name}.mp3"
    async with sem:
        for attempt in range(5):
            try:
                await edge_tts.Communicate(text, VOICE, rate="+0%").save(tmp)
                break
            except Exception as e:
                last = e
                await asyncio.sleep(2 + attempt * 3)
        else:
            print(f"::warning::FAILED {name}: {last}"); return
    # מונו, 24kHz, 32kbps - קטן ועדיין ברור
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp, "-ac", "1", "-ar", "24000", "-b:a", "32k", dst], check=True)

async def safe(n, t):
    try: await one(n, t)
    except Exception as e: print(f"::warning::{n}: {type(e).__name__}: {e}")

async def main():
    await asyncio.gather(*(safe(n, t) for n, t in items.items()))
    done = len(os.listdir(OUT))
    print(f"::notice::clips {done} of {len(items)}")
    if done < len(items) * 0.9: sys.exit(f"::error::only {done} clips")
try:
    asyncio.run(main())
except SystemExit: raise
except Exception as e:
    print(f"::error::{type(e).__name__}: {e}"); raise
