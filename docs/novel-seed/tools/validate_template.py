# -*- coding: utf-8 -*-
"""模板总校验：编号连续 / 引用完整 / 占位符清单 / 核心规则保留"""
import os, io, re, sys

T = r'C:\1111\novel-template'
errors = []

def read(p):
    return io.open(os.path.join(T, p), 'r', encoding='utf-8-sig').read()

def exists(p):
    return os.path.exists(os.path.join(T, p))

print('='*60)
print('小说项目库模板 · 总校验')
print('='*60)

# 1. 核心文件齐全
core = [
 '项目导引/001_总路由.md','项目导引/002_全局记忆.md','项目导引/003_项目目录.md',
 '项目中枢/005_项目核心.md','项目中枢/006_进度.md',
 '项目中枢/写文规则/012_场景卡_写新章.md',
 '项目中枢/文章精修规则/061_规则调用路由.md',
 '数据库维护/055_约束.md','数据库维护/056_变更日志.md','数据库维护/057_度量.md',
]
# 修正路径拼写
core[6] = '项目中枢/文章精修规则/061_规则调用路由.md'
missing = [p for p in core if not exists(p)]
print('[1] 核心文件:', len(core)-len(missing), '/', len(core), '齐全' if not missing else f'缺 {missing}')
if missing: errors.append(f'缺核心文件 {missing}')

# 2. 目录骨架
dirs = ['项目导引','项目中枢','核心资料库','正文','草稿','封面','参考资料','数据库维护','tools']
missdir = [d for d in dirs if not os.path.isdir(os.path.join(T,d))]
print('[2] 九大目录:', len(dirs)-len(missdir), '/', len(dirs))
if missdir: errors.append(f'缺目录 {missdir}')

# 3. 门牌
doors = []
SKIP_PKG=os.path.join(T,'skill-package')
for base,ds,fs in os.walk(T):
    if base.startswith(SKIP_PKG): continue
    for f in fs:
        if f in ('000__子路由.md','_子路由.md'):
            doors.append(os.path.relpath(os.path.join(base,f),T))
print('[3] 门牌文件:', len(doors), '个')

# 4. 核心规则保留检查（铁律四句 / 约束四条 / 061 路由 062-083）
checks = [
 ('002 四条铁律', '项目导引/002_全局记忆.md', ['所有决定都要跟用户确认','不擅自做主','每次回复后面带推荐方案选项','重复确认用户表达的意思再动手']),
 ('055 四约束', '数据库维护/055_约束.md', ['唯一事实源','引用完整性','命名规范','写权限']),
 ('061 路由表', '项目中枢/文章精修规则/061_规则调用路由.md', ['062_精修流程.md','083_报告交付.md']),
 ('012 P0-P4', '项目中枢/写文规则/012_场景卡_写新章.md', ['P0','P1','P2','P3','P4']),
 ('057 四指标', '数据库维护/057_度量.md', ['新增文件数','孤儿','死链','检索跳数']),
]
for name, path, needles in checks:
    if not exists(path):
        errors.append(f'{name}: 文件缺失'); continue
    txt = read(path)
    miss = [n for n in needles if n not in txt]
    print(f'[4] {name}:', '全部保留 OK' if not miss else f'缺 {miss}')
    if miss: errors.append(f'{name} 缺规则 {miss}')

# 5. 占位符清单
ph = {}
for base,ds,fs in os.walk(T):
    for f in fs:
        if not f.endswith(('.md','.txt','.html')): continue
        p = os.path.join(base,f)
        txt = io.open(p,'r',encoding='utf-8-sig',errors='ignore').read()
        found = re.findall(r'\{\{[^}]+\}\}', txt)
        if found:
            ph[os.path.relpath(p,T)] = sorted(set(found))
print('[5] 含占位符文件:', len(ph), '个')
for p,v in sorted(ph.items()):
    print('   ', p, '->', ','.join(v[:6]), '...' if len(v)>6 else '')

# 6. 失效引用扫描（引用了 md 但目标不存在）
allfiles = []
for b,d,f in os.walk(T):
    for x in f: allfiles.append(os.path.join(b,x))
broken = []
for base,ds,fs in os.walk(T):
    for f in fs:
        if not f.endswith('.md'): continue
        p = os.path.join(base,f)
        rel = os.path.relpath(p,T)
        if f.startswith('VALIDATION_'): continue  # 校验报告提到源文件名属正常，非模板引用
        txt = io.open(p,'r',encoding='utf-8-sig',errors='ignore').read()
        for m in re.finditer(r'`([^`]+\.md)`', txt):
            ref = m.group(1).split('/')[-1]
            # 跳过非真实引用：占位 / 命名示例(NNN_/第N) / 退役件 / App层副本 / 历史存档 / 说明类
            if ('{{' in ref or '_废弃_' in ref or ref.startswith('NNN_') or
                ref.startswith('第N') or ref in ('GLOBAL.md','_文件夹说明.md') or
                ref.startswith('进度历史存档_') or ref.startswith('_对标') or
                ref.startswith('_文件夹说明')):
                continue
            # 全库找同名文件
            found = any(ref == x for x in [os.path.basename(q) for q in allfiles])
            if not found:
                broken.append((rel, ref))
print('[6] 失效引用:', len(broken))
for r,b in broken[:15]: print('   ', r, '->', b)
if broken: errors.append(f'{len(broken)} 处失效引用')

print('\n'+'='*60)
print('附：本书残留扫描（模板里不该出现的具体名词）')
all_txt = ''
for base,ds,fs in os.walk(T):
    for f in fs:
        if f.endswith(('.md','.txt')):
            all_txt += io.open(os.path.join(base,f),'r',encoding='utf-8-sig',errors='ignore').read()
for term in ['师尊别装了','叶上天','苏晚','白九','姜璃','周吟霜','凤舞','全球修仙','109','104章']:
    n = all_txt.count(term)
    if n: print(f'   "{term}" 出现 {n} 次')

if errors:
    print('发现问题', len(errors), '类:')
    for e in errors: print('  -', e)
    sys.exit(1)
else:
    print('总判定: PASS —— 核心文件齐全、规则原文保留、目录完整')
