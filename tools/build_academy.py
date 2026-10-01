"""ניקוד לפי רשימת האקדמיה ללשון העברית (tools/academy_2022.xlsx) - גובר על הניקוד הקודם"""
import json, re, sys
sys.path.insert(0, 'tools'); from nikud_tools import strip
from openpyxl import load_workbook
ws = load_workbook('tools/academy_2022.xlsx', read_only=True).worksheets[0]
def N(s):
    # גרש בודד (ג', ח') נשאר - "גת" ו"ג'ת" הם יישובים שונים. מירכאות (''/"/״) יורדות
    s = strip(s or ''); s = re.sub(r'\(.*?\)', '', s)
    s = s.replace("''", '').replace('"', '').replace('״', '').replace('׳', "'").replace('`', "'")
    return re.sub(r'[\s\-־–,]', '', s)
ac = {}
for r in list(ws.iter_rows(values_only=True))[7:]:
    if r[0] and r[1]:
        voc = re.sub(r'\s*\(.*?\)\s*', '', r[1]).strip().replace('"', '').replace('״', '')
        ac.setdefault(N(r[0]), voc)
names = json.load(open('tools/voice_names.json', encoding='utf-8'))['areas']
cur = json.load(open('tools/pronounce.json', encoding='utf-8'))
AI = json.load(open('tools/nikud_ai.json', encoding='utf-8'))
out = {}; src = {}
for n in names:
    if N(n) in ac: out[n] = ac[N(n)]; src[n] = 'academy'; continue
    parts = re.split(r'(\s+-\s*|\s*-\s+|,\s*)', n)
    if not any(N(p) in ac for p in parts[::2]):
        out[n] = cur.get(n, n); src[n] = 'claude'; continue
    # יישור מילים: המקור מול הניקוד הקודם
    base = AI[n][0] if n in AI else cur.get(n, n)   # הניקוד של Claude לשאר החלקים
    ow = [w for w in re.split(r'[\s,\-]+', n) if w]
    cw = [w for w in re.split(r'[\s,]+', base) if w]
    if len(ow) != len(cw):   # אי אפשר ליישר מילים (קיצורים שהורחבו) - כל השם לפי Claude
        out[n] = base; src[n] = 'claude'; continue
    wm = dict(zip(ow, cw))
    res = []
    for i, p in enumerate(parts):
        if i % 2: res.append(', ' if ',' in p else ' '); continue
        if N(p) in ac: res.append(ac[N(p)])
        else: res.append(' '.join(wm.get(w, w) for w in p.split()))
    out[n] = re.sub(r'\s+', ' ', ''.join(res)).strip(); src[n] = 'academy+claude'
# תיקונים ידניים (גוברים על הכל)
import os
if os.path.exists('tools/pronounce_manual.json'):
    for k, v in json.load(open('tools/pronounce_manual.json', encoding='utf-8')).items(): out[k] = v; src[k] = 'manual'
# אזורי תעשייה: "אזור תעשייה <מקום>" / "אזור תעשייה צפוני <מקום>" (צמוד, בלי הפסקה)
AZ = 'אֵזוֹר תַּעֲשִׂיָּה'
DIRS = {'צפוני': 'צְפוֹנִי', 'דרומי': 'דְּרוֹמִי', 'הדרומי': 'דְּרוֹמִי'}
for n in names:
    ow = [w for w in re.split(r'[\s,]+|\s-\s?|\s?-\s', n) if w]
    vw = [w for w in re.split(r'[\s,]+', out[n]) if w]
    if len(ow) != len(vw): continue
    if ow[:2] in (['אזור', 'תעשייה'], ['איזור', 'תעשייה']):
        rest_o, rest_v, d = ow[2:], vw[2:], ''
        if rest_o and rest_o[0] in DIRS: d = ' ' + DIRS[rest_o[0]]; rest_v = rest_v[1:]
        if rest_v: out[n] = AZ + d + ' ' + ' '.join(rest_v)
    elif 'תעשייה' in ow and ow.index('תעשייה') >= 2 and ow[ow.index('תעשייה') - 1] in ('אזור', 'איזור'):
        i = ow.index('תעשייה'); before_v = vw[:i - 1]; after_o = ow[i + 1:]; after_v = vw[i + 1:]
        d = ''
        if after_o and after_o[0] in DIRS: d = ' ' + DIRS[after_o[0]]; after_v = after_v[1:]
        out[n] = AZ + d + ' ' + ', '.join(x for x in [' '.join(after_v), ' '.join(before_v)] if x)
    elif ow[:2] == ['פארק', 'תעשיות']:
        out[n] = ' '.join(vw)
# תיקונים ידניים (גוברים על הכל)
import os
if os.path.exists('tools/pronounce_manual.json'):
    for k, v in json.load(open('tools/pronounce_manual.json', encoding='utf-8')).items(): out[k] = v; src[k] = 'manual'
json.dump(out, open('tools/pronounce.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
json.dump(src, open('tools/nikud_source.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
from collections import Counter; print(Counter(src.values()))
