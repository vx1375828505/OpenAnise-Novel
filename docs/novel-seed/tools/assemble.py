# -*- coding: utf-8 -*-
"""
OpenAnise-Novel 技能包移植 · 总装脚本
把 B1/B2/B3/B4 四批产出拼成完整的技能资产树，并做全局一致性校验。
运行：python assemble.py（在本脚本所在目录）
"""
import os, io, json, shutil, sys, re

ROOT = r'C:\1111\openanise-skills'
OUT  = os.path.join(ROOT, 'out')
FINAL = os.path.join(ROOT, 'final', 'skills-novel')   # 最终的 assets/skills-novel/

def log(*a): print(*a, flush=True)

def reset_dir(p):
    if os.path.isdir(p): shutil.rmtree(p)
    os.makedirs(p, exist_ok=True)

def main():
    log('='*64)
    log('OpenAnise-Novel 技能包移植 · 总装')
    log('='*64)

    id_map = json.load(io.open(os.path.join(ROOT,'id_map.json'),'r',encoding='utf-8'))
    reset_dir(FINAL)

    errors=[]

    # ---- B2: 25 流派技能（目录名 = new_id）----
    genres_src = os.path.join(OUT,'genres')
    n_genre=0
    mp=json.load(io.open(os.path.join(ROOT,'id_map.json'),'r',encoding='utf-8'))
    for entry in mp:
        sid = entry['new_id']
        src = os.path.join(genres_src, sid, 'SKILL.md')
        if not os.path.exists(src):
            errors.append(f'[B2] 缺文件 {sid}/SKILL.md'); continue
        txt = io.open(src,'r',encoding='utf-8').read()
        # 字段校验：只准 name/version/description 三字段
        if 'trigger:' in txt: errors.append(f'[B2] {sid} 残留 trigger 字段')
        if '\nid:' in txt or txt.startswith('id:'): errors.append(f'[B2] {sid} 残留 id 字段')
        if f'version: 1.0.0' not in txt: errors.append(f'[B2] {sid} 缺 version')
        dst = os.path.join(FINAL, sid, 'SKILL.md')
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        n_genre+=1
    log(f'[B2] 装入流派技能: {n_genre}/25')

    # ---- 交叉校验：B1 播种器 id/name 与 B2 目录、id_map 三方一致 ----
    kt_path = os.path.join(OUT,'kotlin','SeedNovelSkills.kt')
    if os.path.exists(kt_path):
        kt = io.open(kt_path,'r',encoding='utf-8').read()
        b1 = re.findall(r'NovelSeedEntry\("(genre-[a-z_]+)",\s*"([^"]+)"', kt)
        b1_ids = {x[0] for x in b1}
        b2_ids = {e['new_id'] for e in mp}
        mp_name = {e['new_id']: e['name'] for e in mp}
        if b1_ids != b2_ids:
            errors.append(f'[交叉] B1 播种器 id 与 id_map 差异: {b1_ids ^ b2_ids}')
        bad_name = [(i,n) for i,n in b1 if mp_name.get(i)!=n]
        if bad_name:
            errors.append(f'[交叉] B1 name 与 id_map 不一致: {bad_name}')
        log(f'[交叉] B1↔B2↔id_map: {len(b1_ids)} 个 id 逐项一致, name 不一致 {len(bad_name)} 个')
        # 父技能 novel-writing 必须被播种（B3 产出的父技能要有安装入口）
        # 播种器用 NovelSkillGuard.PARENT_ID 常量（值 "novel-writing"），需解析常量引用
        guard_path = os.path.join(OUT,'kotlin','NovelSkillGuard.kt')
        parent_ok = '"novel-writing"' in kt
        if not parent_ok and os.path.exists(guard_path):
            gt = io.open(guard_path,'r',encoding='utf-8').read()
            m = re.search(r'PARENT_ID\s*=\s*"([^"]+)"', gt)
            if m and m.group(1)=='novel-writing' and 'PARENT_ID' in kt:
                parent_ok = True
        if parent_ok:
            log('[交叉] 父技能 novel-writing 已纳入播种器（含常量引用解析）')
        else:
            errors.append('[交叉] 父技能 novel-writing 未纳入播种器——B3 产出将无法生效')
    else:
        errors.append('[交叉] 缺 B1 播种器 SeedNovelSkills.kt')

    # ---- B3: novel-writing 父技能 + references ----
    sub_src = os.path.join(OUT,'subagents','novel-writing')
    n_sub=0
    if os.path.isdir(sub_src):
        for base,dirs,files in os.walk(sub_src):
            for f in files:
                if not f.endswith('.md'): continue
                src = os.path.join(base,f)
                rel = os.path.relpath(src, sub_src)
                dst = os.path.join(FINAL,'novel-writing',rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copyfile(src,dst)
                n_sub+=1
        # 校验父技能 SKILL.md frontmatter 只准 name/version/description 三字段
        parent_md = os.path.join(sub_src, 'SKILL.md')
        if os.path.exists(parent_md):
            ptxt = io.open(parent_md,'r',encoding='utf-8').read()
            if '\nid:' in ptxt or ptxt.startswith('id:'):
                errors.append('[B3] 父技能 SKILL.md 残留 id: 字段（§3.2 只准 name/version/description）')
            if 'version: 1.0.0' not in ptxt:
                errors.append('[B3] 父技能 SKILL.md 缺 version')
            fields = re.findall(r'^([a-z_]+):', ptxt.split('---')[1], re.M) if ptxt.startswith('---') else []
            if fields != ['name','version','description']:
                errors.append(f'[B3] 父技能 frontmatter 字段异常: {fields}')
            log(f'[B3] 父技能 frontmatter 字段: {fields}')

        # 校验：references 里不得有旧工具的「调用形态」。
        # 说明性提及（如「本文件取代 novel_delegate_subagent」）是合规的，
        # 只检测 `工具名(` / `工具名.`/ `调用 工具名` 这类实际使用形态。
        old_tools = ['novel_delegate_subagent','novel_get_outline','novel_build_context',
                     'novel_materials_get','novel_relationships','novel_hooks_status',
                     'novel_get_chapter','novel_chapter_stats','novel_consistency_check',
                     'novel_query_previous']
        for base,dirs,files in os.walk(os.path.join(FINAL,'novel-writing')):
            for f in files:
                if not f.endswith('.md'): continue
                p=os.path.join(base,f)
                txt=io.open(p,'r',encoding='utf-8').read()
                for t in old_tools:
                    # 调用形态：工具名紧跟 ( 或 . 或空格+调用动词；排除引号内的说明性提及
                    for m in re.finditer(re.escape(t), txt):
                        after = txt[m.end():m.end()+3]
                        before = txt[max(0,m.start()-12):m.start()]
                        if after.startswith('(') or after.startswith('.'):
                            errors.append(f'[B3] {os.path.relpath(p,FINAL)} 调用旧工具 {t}(')
                            break
    log(f'[B3] 装入子代理文件: {n_sub}')

    # ---- 汇总清单 ----
    total_md = sum(1 for b,d,fs in os.walk(FINAL) for f in fs if f.endswith('.md'))
    tree=[]
    for base,dirs,files in os.walk(FINAL):
        for f in sorted(files):
            p=os.path.join(base,f)
            tree.append((os.path.relpath(p,FINAL).replace('\\','/'), os.path.getsize(p)))
    manifest={'skills':len([t for t in tree if t[0].endswith('SKILL.md')]),
              'markdown_total':len(tree),
              'genre_skills':n_genre,'subagent_files':n_sub,
              'files':[{'path':p,'bytes':s} for p,s in sorted(tree)]}
    mf=os.path.join(ROOT,'final','manifest.json')
    io.open(mf,'w',encoding='utf-8').write(json.dumps(manifest,ensure_ascii=False,indent=2))
    log(f'[汇总] SKILL.md 总数: {manifest["skills"]} (期望 26 = 25 流派 + novel-writing)')
    log(f'[汇总] Markdown 文件总数: {len(tree)}')
    log(f'[汇总] 清单: {mf}')

    if errors:
        log('\n'+'!'*64)
        log(f'发现 {len(errors)} 个问题:')
        for e in errors[:40]: log('  ',e)
        log('!'*64)
        sys.exit(1)
    else:
        log('\n全部校验通过。')

if __name__=='__main__':
    main()
