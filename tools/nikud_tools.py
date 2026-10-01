"""ניקוד מהרשימה של טל: זיהוי מילים עם ניקוד תקין / שגוי (שווא מתחת לכל אות)"""
import re
MARK = re.compile('[֑-ׇ]')
VOW = set(chr(c) for c in range(0x05B1, 0x05BC)) | {'ׇ'}
SH = 'ְ'
def strip(w): return MARK.sub('', w)
def loose(w): return re.sub('[וי\'"״׳\\-־.,]', '', strip(w))
def vowels(w):
    # תנועות + שורוק (וּ שלא בתחילת מילה כעיצור)
    return sum(c in VOW for c in w) + len(re.findall('וּ', w))
def good(w):
    L = [c for c in w if 'א' <= c <= 'ת']
    if not L: return True
    v = vowels(w)
    if v == 0: return len(L) <= 1
    sh = w.count(SH)
    return not (sh >= len(L) - 1 and len(L) >= 3 and v <= 1)
