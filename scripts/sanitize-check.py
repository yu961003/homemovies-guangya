#!/usr/bin/env python3
"""脱敏回归检查（v2 改造时使用的三条规则）。

用法（基线文件包含解码后的字面量，**只放在本机临时目录，切勿提交或打包**）：
    python scripts/sanitize-check.py baseline <原始脱敏源码根目录> /tmp/sanitize-baseline.json
    python scripts/sanitize-check.py check    <改动后源码根目录>   /tmp/sanitize-baseline.json


R1 每个原转义字面量（原样字节）在新树中的出现次数 >= 基线（允许挪文件，不允许消失/被改写成明文）。
R2 「协议/敏感」字面量（来自 ui/ 之外的文件：协议字段名、客户端标识、实体别名等）解码后的文本，
   以明文出现在 .kt/.kts/.java 任何位置（含注释）的次数 <= 基线。
R3 「界面文案」字面量（来自 ui/ 下文件）解码后的完整文本，作为**独立明文字符串字面量** "..." 的出现次数 <= 基线。
   （常用词出现在新注释或更长文案里不算还原；但不允许新写出与转义字面量完全相同的明文字面量。）
"""
import json, os, re, sys
ESC_LIT = re.compile(r'"((?:[^"\\\n]|\\.)*\\u[0-9a-fA-F]{4}(?:[^"\\\n]|\\.)*)"')
UNI = re.compile(r'\\u([0-9a-fA-F]{4})')
EXTS = ('.kt', '.kts', '.java')

def files(root):
    for d, _, fs in os.walk(root):
        if '/build' in d or '/.git' in d: continue
        for f in fs:
            if f.endswith(EXTS): yield os.path.join(d, f)

def decode(s): return UNI.sub(lambda x: chr(int(x.group(1), 16)), s)

def runs(lit):
    out = []
    for m in re.finditer(r'(?:\\u[0-9a-fA-F]{4})+', lit):
        t = decode(m.group(0)).strip()
        if len(t) >= 2 and any(ord(c) > 0x7f for c in t): out.append(t)
    return out

def scan(root):
    lits, texts = {}, []
    for p in files(root):
        t = open(p, encoding='utf-8', errors='replace').read()
        texts.append(t)
        rel = os.path.relpath(p, root)
        for m in ESC_LIT.finditer(t):
            lits.setdefault(m.group(0), []).append(rel)
    return lits, '\n'.join(texts)

def main():
    mode, root, db = sys.argv[1:4]
    lits, text = scan(root)
    if mode == 'baseline':
        sens, ui = {}, {}
        for lit, where in lits.items():
            is_ui = all('/ui/' in w for w in where)
            for r in runs(lit):
                if is_ui: ui[decode(lit[1:-1])] = text.count('"' + decode(lit[1:-1]) + '"')
                else: sens[r] = text.count(r)
        json.dump({'lits': {k: len(v) for k, v in lits.items()}, 'sens': sens, 'ui': ui},
                  open(db, 'w'), ensure_ascii=False, indent=0)
        print('baseline: literals=%d sensitive_runs=%d ui_literals=%d' % (len(lits), len(sens), len(ui)))
        return
    base = json.load(open(db)); bad = 0
    for lit, n in base['lits'].items():
        c = len(lits.get(lit, []))
        if c < n: print('R1 MISSING (%d<%d): %s' % (c, n, lit[:100])); bad += 1
    for r, n in base['sens'].items():
        c = text.count(r)
        if c > n: print('R2 SENSITIVE PLAINTEXT (%d>%d): %r' % (c, n, r)); bad += 1
    for u, n in base['ui'].items():
        c = text.count('"' + u + '"')
        if c > n: print('R3 UI LITERAL RE-INTRODUCED (%d>%d): %r' % (c, n, u)); bad += 1
    print('R1 literals checked=%d, R2 sensitive=%d, R3 ui=%d, problems=%d' % (len(base['lits']), len(base['sens']), len(base['ui']), bad))
    sys.exit(1 if bad else 0)
main()
