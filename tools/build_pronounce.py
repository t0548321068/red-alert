"""בונה את tools/pronounce.json מהרשימה המנוקדת (רק ניקוד תקין, התאמה זהירה)"""
import json, re, sys
sys.path.insert(0, 'tools'); from nikud_tools import *
lines = open('tools/nikud_list.txt', encoding='utf-8').read().split('\n')
exact = {}
for l in lines:
    t = l.strip()
    if not t or t.startswith('=') or 'רשימת' in t: continue
    for w in re.split(r'[\s־,]+', t):
        w = w.strip('-')
        if w and good(w) and any('֑' <= c <= 'ׇ' for c in w):
            exact.setdefault(strip(w), w)
# כתיב מלא -> חסר: רק אות כפולה (וו/יי), ועוד כמה מקרים שנבדקו ידנית
SAFE = {'אום': 'אם', 'אדומים': 'אדמים', 'איזור': 'אזור', 'עילית': 'עלית'}
BLOCK = {'איילת'}
def lookup(w):
    if w in exact: return exact[w]
    if w in SAFE and SAFE[w] in exact: return exact[SAFE[w]]
    if w not in BLOCK:
        d = w.replace('וו', 'ו').replace('יי', 'י')
        if d != w and d in exact: return exact[d]
    if w.startswith('ו') and len(w) > 2:
        r = lookup(w[1:])
        if r: return 'וְ' + r
    return None
names = json.load(open('tools/voice_names.json', encoding='utf-8'))['areas']
fix = {}
for n in names:
    out = []; hit = 0
    for w in re.split(r'(\s+|-|,)', n):
        if not w.strip() or w in ('-', ','): out.append(' ' if w == '-' else w); continue
        v = lookup(w)
        if v: hit += 1; out.append(v)
        else: out.append(w)
    if hit: fix[n] = re.sub(r'\s+', ' ', ''.join(out)).strip()
# ניקוד מלא שנעשה בעזרת Claude (גובר על הרשימה)
import os
if os.path.exists('tools/nikud_ai.json'):
    for k, (v, c) in json.load(open('tools/nikud_ai.json', encoding='utf-8')).items(): fix[k] = v
json.dump(fix, open('tools/pronounce.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('mapped', len(fix))
