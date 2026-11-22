#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B2 流派批次：把 25 个原始流派 SKILL.md 批量改造成新机制 SKILL.md。

权威依据：SKILL_PORTING_PLAN §3.2（字段级对照）+ §4.2（完整改造样例）
  - frontmatter 只保留 name / version / description 三字段（顺序固定）
  - 不写 id 字段：id 由播种器另给，经目录名 genre-<slug> 与 NOVEL_SKILL_PACK 体现
  - 删除 trigger 字段：把 3~5 个最关键触发词融入 description
  - description 改写为「规约 + 触发场景」结构，≤195 字符，逐文件定制
  - 正文（frontmatter 结束的 `---` 之后到文末）逐字零改

铁律：大文件用 Python 精确替换 + 断言（count == 1），不做正则的宽泛改写。
"""

import json
import os
import re
import sys
import difflib

ROOT = r"C:\1111\openanise-skills"
IDMAP_PATH = os.path.join(ROOT, "id_map.json")
SRC_BASE = os.path.join(ROOT, "ref-old", "skills", "penai")
OUT_BASE = os.path.join(ROOT, "out", "genres")
REPORT_PATH = os.path.join(OUT_BASE, "VALIDATION.txt")
MAX_DESC = 195

# 逐文件定制：从源 description 提取规约核心 + 从源 trigger 取 3~5 个关键词，
# 融入「写/续写…时必读」句式。参照 §4.2 样例，不套空模板。
DESCRIPTIONS = {
    "genre-urban_zhuixu":
        "番茄都市赘婿流派规约：前期隐忍被羞辱，后期身份揭露集中打脸。写/续写赘婿、隐忍流、身份揭露打脸、护短、情绪压抑爆发类都市小说时必读。",
    "genre-urban_shenyi":
        "番茄都市神医流派规约：医术通天，救人打脸两不误，专业术语配震撼效果。写/续写都市神医、一针定乾坤、中医爽文、银针救人、医术打脸类小说时必读。",
    "genre-urban_zhanshen":
        "番茄都市战神流派规约：沙场战神回归都市，护妻女、灭仇敌，铁血柔情。写/续写都市战神、龙帅归来、强者归来、护妻、身份揭露类小说时必读。",
    "genre-fantasy_qiandao":
        "番茄玄幻签到流派规约：签到系统送奖励，一路碾压，爽点密集。写/续写玄幻签到、开局无敌、签到系统、领奖流、碾压爽文类小说时必读。",
    "genre-fantasy_renwu":
        "番茄玄幻系统流派规约：系统发布任务，完成任务变强，任务设计有趣。写/续写玄幻系统、任务狂魔、系统任务、骚操作、趣味任务文类小说时必读。",
    "genre-fantasy_wudi":
        "番茄玄幻无敌流派规约：开局满级，一路横推，绝对无敌。写/续写玄幻无敌、横推诸天、无敌流、横推碾压、满级开局类小说时必读。",
    "genre-chuanyue_zhongtian":
        "番茄穿越种田流派规约：穿越古代异世界，用现代知识种田经商，温馨日常配偶尔打脸。写/续写穿越种田、发家致富、古代经商、温馨日常、现代知识类小说时必读。",
    "genre-chuanyue_niandai":
        "番茄穿越年代流派规约：穿越七八十年代，抓时代机遇发家，年代感配爽感。写/续写穿越年代、改革开放、年代文、商机赚钱、万元户类小说时必读。",
    "genre-xuanyi_lingyi":
        "番茄悬疑灵异流派规约：灵异事件加悬疑解谜，恐怖氛围配揭秘爽感。写/续写悬疑灵异、捉鬼天师、灵异探案、恐怖氛围、悬疑揭秘类小说时必读。",
    "genre-xuanyi_daomu":
        "番茄盗墓探险流派规约：古墓探险加风水玄学，惊险刺激配宝物收获。写/续写盗墓探险、寻龙点穴、古墓、风水玄学、探宝类小说时必读。",
    "genre-guize_guaitan":
        "番茄规则怪谈流派规约：诡异副本加隐藏规则，乐子人主角发疯破局，男频女频通杀。写/续写规则怪谈、发疯破局、无限流副本、乐子人、单元剧类小说时必读。",
    "genre-xiancheng_zhenxing":
        "番茄县城振兴流派规约：重生贫困县城，绑定振兴系统，全民分红逆袭。写/续写县城振兴、全民分红、集体致富、基建流、重生逆袭类小说时必读。",
    "genre-xiuxian_zc":
        "番茄修仙职场流派规约：宗门变互联网公司，修炼变打工人KPI，摸鱼也能涨修为。写/续写修仙职场、KPI考核、宗门打工、摸鱼涨修为、社畜共鸣类小说时必读。",
    "genre-female_gaojian":
        "番茄无CP大女主流派规约：彻底告别恋爱脑，男人皆是工具人，主线只有搞钱复仇。写/续写无CP大女主、搞钱流、女频复仇、商业帝国、搞钱复仇类小说时必读。",
    "genre-qingxu_fafeng":
        "番茄情绪发疯流派规约：与其内耗自己不如创死别人，系统奖励发疯行为。写/续写情绪发疯、怼人变强、发疯系统、怼人流、情绪价值类小说时必读。",
    "genre-changsheng_jiazu":
        "番茄长生家族流派规约：家族传承千年，层层揭秘底蕴实力，打脸各方势力。写/续写长生家族、千年底蕴、家族传承、层层揭秘、底蕴打脸类小说时必读。",
    "genre-tianming_fanpai":
        "番茄天命反派流派规约：穿越成反派，能编辑自己与他人背景设定，反杀天命之子。写/续写天命反派、背景编辑、反派逆袭、天命之子、收割气运类小说时必读。",
    "genre-edu_nvpei":
        "番茄恶毒女配流派规约：穿成恶毒女配，带全家反派逆袭洗白，打脸原男女主。写/续写恶毒女配、全家反派洗白、洗白团宠、打脸原主、女频逆袭类小说时必读。",
    "genre-dushi_shouye":
        "番茄都市高武守夜人流派规约：国家特殊部门加民族大义，异能对抗外来神明，热血燃爆。写/续写都市高武、守夜人、民族大义、异能战斗、热血燃爆类小说时必读。",
    "genre-niandai_qinqing":
        "番茄年代重生流派规约：重生七八十年代，手撕极品亲戚加弥补遗憾，中老年读者收割机。写/续写年代重生、整顿亲情、极品亲戚、弥补遗憾、年代爽文类小说时必读。",
    "genre-zhiye_xt":
        "番茄职业系统流派规约：把普通职业变成超能力，法医外卖员快递员都能变强。写/续写职业系统、超能力、日常职业、法医外卖员、职业逆袭类小说时必读。",
    "genre-nvxing_xingzhen":
        "番茄女性悬疑刑侦流派规约：女性主角加刑侦破案加心理博弈，平台重点扶持。写/续写女性悬疑、刑侦破案、女性主角、心理博弈、探案推理类小说时必读。",
    "genre-moyu_bailan":
        "番茄摸鱼摆烂流派规约：越摸鱼越强，越摆烂越成功，打工人最强共鸣。写/续写摸鱼摆烂、变强流、摸鱼系统、反内卷、打工人共鸣类小说时必读。",
    "genre-zhibo_nixi":
        "番茄直播短视频流派规约：靠直播短视频逆袭成全网顶流，紧跟热点。写/续写直播短视频、逆袭、网红、弹幕互动、热点运营类小说时必读。",
    "genre-honghuang_bianji":
        "番茄洪荒神话流派规约：能修改洪荒历史与神话设定，三清都懵了。写/续写洪荒神话、编辑流、神话改编、洪荒脑洞、改写历史类小说时必读。",
}


def read_text(path):
    """按二进制读取后以 UTF-8 解码，写回时原样编码，保证字节级保真。"""
    with open(path, "rb") as f:
        raw = f.read()
    assert not raw.startswith(b"\xef\xbb\xbf"), f"{path}: 意外出现 BOM"
    return raw.decode("utf-8")


def split_frontmatter(txt):
    """切分为 (frontmatter_block 含两端 --- 及尾换行, body 逐字保留)。"""
    nl = "\r\n" if "\r\n" in txt else "\n"
    lines = txt.split(nl)
    assert lines and lines[0] == "---", "首行不是 `---`"
    close = next((i for i in range(1, len(lines)) if lines[i] == "---"), None)
    assert close is not None, "未找到 frontmatter 结束的 `---`"
    fm_block = nl.join(lines[: close + 1]) + nl
    body = nl.join(lines[close + 1 :])
    assert fm_block + body == txt, "frontmatter/正文切分重建不一致"
    return fm_block, body, nl


def parse_fields(fm_block, nl):
    lines = fm_block[: -len(nl)].split(nl)
    assert lines[0] == "---" and lines[-1] == "---", "frontmatter 边界异常"
    fields = []
    for line in lines[1:-1]:
        assert ":" in line, f"frontmatter 行缺少冒号: {line!r}"
        key, value = line.split(":", 1)
        fields.append((key.strip(), value.strip()))
    return fields


def transform_all():
    with open(IDMAP_PATH, "r", encoding="utf-8") as f:
        idmap = json.load(f)
    assert len(idmap) == 25, f"id_map.json 条目数应为 25，实际 {len(idmap)}"
    assert set(DESCRIPTIONS) == {e["new_id"] for e in idmap}, "description 映射与 id_map 不一致"

    os.makedirs(OUT_BASE, exist_ok=True)
    records = []

    for entry in idmap:
        genre = entry["genre_id"]
        new_id = entry["new_id"]
        src_rel = entry["file"]
        assert src_rel.endswith(f"/{genre}/SKILL.md"), f"{genre}: file 路径异常"
        src_path = os.path.join(SRC_BASE, genre, "SKILL.md")
        assert os.path.isfile(src_path), f"源文件缺失: {src_path}"

        txt = read_text(src_path)
        fm_block, body, nl = split_frontmatter(txt)
        fields = parse_fields(fm_block, nl)
        keys = [k for k, _ in fields]
        assert keys == ["name", "description", "trigger"], (
            f"{genre}: 源 frontmatter 字段顺序异常: {keys}"
        )

        name = dict(fields)["name"]
        desc = DESCRIPTIONS[new_id]
        assert len(desc) <= MAX_DESC, f"{new_id}: description 超限 {len(desc)}"

        new_lines = [
            "---",
            f"name: {name}",
            "version: 1.0.0",
            f"description: {desc}",
            "---",
        ]
        new_fm = nl.join(new_lines) + nl

        # ---- 铁律：精确替换 + 断言 count == 1 ----
        assert txt.count(fm_block) == 1, (
            f"{genre}: 精确替换断言失败，frontmatter 块出现次数 != 1"
        )
        new_txt = txt.replace(fm_block, new_fm, 1)

        # 正文逐字零改断言
        assert new_txt[len(new_fm) :] == body, f"{genre}: 正文被改动"

        out_dir = os.path.join(OUT_BASE, new_id)
        os.makedirs(out_dir, exist_ok=True)
        out_path = os.path.join(out_dir, "SKILL.md")
        with open(out_path, "wb") as f:
            f.write(new_txt.encode("utf-8"))

        records.append(
            {
                "genre": genre,
                "new_id": new_id,
                "src_path": src_path,
                "out_path": out_path,
                "body": body,
                "src_name": name,
                "src_desc": dict(fields)["description"],
                "src_trig": dict(fields)["trigger"],
                "new_desc": desc,
            }
        )

    return records


def validate(records):
    lines, errors = [], []

    def log(s=""):
        lines.append(s)

    with open(IDMAP_PATH, "r", encoding="utf-8") as f:
        idmap = json.load(f)
    expected_ids = {e["new_id"] for e in idmap}

    log("=" * 72)
    log("OpenAnise-Novel 技能包移植 · B2 流派批次 校验报告（§3.2/§4.2 权威版）")
    log("=" * 72)
    log(f"源资产目录: {SRC_BASE}")
    log(f"产出目录  : {OUT_BASE}")
    log(f"id_map 条目: {len(idmap)}")
    log()

    # 1) 文件齐全
    log("[1] 产出文件齐全性检查")
    missing = [r["out_path"] for r in records if not os.path.isfile(r["out_path"])]
    log(f"  - 应产出 25 个；实际产出 {len(records)} 个；缺失 {len(missing)} 个")
    if missing:
        errors.extend(f"缺失: {m}" for m in missing)
    if len(records) != 25:
        errors.append(f"产出记录数 != 25: {len(records)}")
    log(f"  - 结果: {'PASS' if not missing and len(records) == 25 else 'FAIL'}")
    log()

    # 2) 正文逐字一致
    log("[2] 正文逐字一致性检查（diff 差异数必须为 0）")
    total_diff = 0
    for r in records:
        if not os.path.isfile(r["out_path"]):
            continue
        _, out_body, _ = split_frontmatter(read_text(r["out_path"]))
        src_body = r["body"]
        equal = out_body == src_body
        diff_lines = list(
            difflib.unified_diff(
                src_body.splitlines(keepends=True),
                out_body.splitlines(keepends=True),
                fromfile="src", tofile="out",
            )
        )
        n_diff = 0 if equal else len(diff_lines)
        total_diff += n_diff
        log(
            f"  - {r['new_id']:26s} 正文长度={len(src_body):5d} "
            f"逐字相等={equal} 差异数={n_diff}"
        )
        if not equal:
            errors.append(f"{r['new_id']}: 正文被改动（差异数 {n_diff}）")
    log(f"  - 差异总数: {total_diff}（必须为 0）")
    log(f"  - 结果: {'PASS' if total_diff == 0 else 'FAIL'}")
    log()

    # 3) description <= 195 且含触发场景句式
    log("[3] description 长度与结构检查（<= 195 字符）")
    desc_over = 0
    for r in records:
        if not os.path.isfile(r["out_path"]):
            continue
        fm, _, nl = split_frontmatter(read_text(r["out_path"]))
        d = dict(parse_fields(fm, nl))
        desc = d.get("description", "")
        over = len(desc) > MAX_DESC
        structured = "规约：" in desc and "写/续写" in desc and "时必读" in desc
        if over:
            desc_over += 1
            errors.append(f"{r['new_id']}: description 超限 {len(desc)}")
        if not structured:
            errors.append(f"{r['new_id']}: description 非「规约+触发场景」结构")
        log(
            f"  - {r['new_id']:26s} 长度={len(desc):3d} "
            f"{'OVER' if over else 'OK':4s} 规约+触发场景={structured}"
        )
    log(f"  - 超限数: {desc_over}（必须为 0）")
    log(f"  - 结果: {'PASS' if desc_over == 0 else 'FAIL'}")
    log()

    # 4) 目录名与 id_map new_id 一一对应
    log("[4] 目录名与 id_map.json new_id 一一对应检查")
    dirs = []
    for dirpath, _, filenames in os.walk(OUT_BASE):
        if "SKILL.md" in filenames and os.path.abspath(dirpath) != os.path.abspath(OUT_BASE):
            dirs.append(os.path.basename(dirpath))
    dirs_set = set(dirs)
    dup_dirs = len(dirs) - len(dirs_set)
    coverage_ok = dirs_set == expected_ids
    if dup_dirs:
        errors.append(f"目录名重复数: {dup_dirs}")
    if not coverage_ok:
        errors.append(
            f"目录名与 id_map 不一致: 缺少={expected_ids - dirs_set}, 多余={dirs_set - expected_ids}"
        )
    log(f"  - 含 SKILL.md 的目录数: {len(dirs)}（应为 25）")
    log(f"  - 目录名集合 == new_id 集合: {coverage_ok}")
    log(f"  - 目录名重复数: {dup_dirs}（必须为 0）")
    log(f"  - 结果: {'PASS' if len(dirs) == 25 and coverage_ok and dup_dirs == 0 else 'FAIL'}")
    log()

    # 5) trigger / id 字段残留数 = 0
    log("[5] 已废弃字段残留检查（trigger: 与 id: 残留数必须为 0）")
    trig_re = re.compile(r"^\s*trigger\s*:", re.MULTILINE)
    id_re = re.compile(r"^\s*id\s*:", re.MULTILINE)
    trig_total = id_total = 0
    for r in records:
        if not os.path.isfile(r["out_path"]):
            continue
        txt = read_text(r["out_path"])
        ntrig, nid = len(trig_re.findall(txt)), len(id_re.findall(txt))
        trig_total += ntrig
        id_total += nid
        log(f"  - {r['new_id']:26s} trigger:={ntrig} id:={nid}")
        if ntrig or nid:
            errors.append(f"{r['new_id']}: 残留字段 trigger={ntrig} id={nid}")
    log(f"  - trigger: 残留总数: {trig_total}（必须为 0）")
    log(f"  - id: 残留总数: {id_total}（必须为 0）")
    log(f"  - 结果: {'PASS' if trig_total == 0 and id_total == 0 else 'FAIL'}")
    log()

    # 6) frontmatter 三字段与顺序
    log("[6] frontmatter 字段与顺序检查（name / version / description）")
    fm_ok = 0
    for r in records:
        if not os.path.isfile(r["out_path"]):
            continue
        fm, _, nl = split_frontmatter(read_text(r["out_path"]))
        fields = parse_fields(fm, nl)
        keys = [k for k, _ in fields]
        d = dict(fields)
        ok = (
            keys == ["name", "version", "description"]
            and d.get("version") == "1.0.0"
            and d.get("name") == r["src_name"]
        )
        if ok:
            fm_ok += 1
        else:
            errors.append(f"{r['new_id']}: frontmatter 字段/顺序/name 异常: {keys}")
        log(f"  - {r['new_id']:26s} 字段={keys} 顺序与name正确={ok}")
    log(f"  - 正确数: {fm_ok}/25")
    log(f"  - 结果: {'PASS' if fm_ok == 25 else 'FAIL'}")
    log()

    # 汇总
    log("=" * 72)
    if errors:
        log(f"总判定: FAIL（问题数 {len(errors)}）")
        for e in errors:
            log(f"  ! {e}")
    else:
        log("总判定: PASS —— 25 文件齐全、正文逐字一致（差异数 0）、")
        log("         description <=195 且为规约+触发场景结构、目录名与 id_map 一一对应、")
        log("         trigger:/id: 残留均为 0、frontmatter 仅 name/version/description 三字段。")
    log("=" * 72)

    report = "\n".join(lines) + "\n"
    with open(REPORT_PATH, "w", encoding="utf-8") as f:
        f.write(report)
    print(report)
    return 0 if not errors else 1


def main():
    records = transform_all()
    print(f"已产出 {len(records)} 个 SKILL.md 到 {OUT_BASE}\n")
    return validate(records)


if __name__ == "__main__":
    sys.exit(main())
