"""Generate the actual Skills tree from the shared growth catalog; --check rejects drift."""
import argparse
import json
import math
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHARACTER = ROOT / 'bridge-base/elden-ring/mc-bridge/character-bridge'
CATALOG = CHARACTER / 'src/main/resources/data/erbridge/ability_catalog.json'
TREE = CHARACTER / 'src/skills/resources/data/erbridge/puffish_skills/categories/abilities'
PREVIEW = ROOT / 'docs/diagrams/growth-tree-layout.svg'
COLORS = ['#81cdb2', '#ecab89', '#d6bc79', '#9abde2', '#b8a6d9', '#b6ca83']


def effect_text(ability, rank, index):
    """Player-facing benefit; implementation paths belong in the catalog, not the tooltip."""
    limits, key = rank['limits'], ability['id']
    upper = lambda field: f"{limits[field][1]:g}"
    if key == 'speed': return f"地面速度上限 {upper('speed')} 格/tick。可自由调低；不会自动跳跃。"
    if key == 'aura': return f"自动近战距离上限 {upper('range')} 格。单目标、不穿墙，保留武器冷却。"
    if key == 'scaffold': return f"搭桥速度上限 {upper('speed')}，最短放置间隔 {limits['placement_delay'][0]:g} tick。消耗背包中的真实方块。"
    if key == 'fly': return f"创造式飞行，速度上限 {upper('speed')}。本版可悬停；没有时长或高度限制。"
    if key == 'airjump': return '空中可再跳一次。' if index == 0 else '可选择双跳或连续空跳；保留原来的双跳选择。'
    if key == 'step': return f"跨越最高 {upper('height')} 格的台阶。"
    if key == 'velocity': return f"水平击退至少保留 {limits['horizontal'][0]*100:g}%，垂直至少保留 {limits['vertical'][0]*100:g}%。"
    if key == 'nametags': return f"显示附近敌人的名字和血量，最大距离 {upper('distance')} 格。"
    return {'safewalk':'减少走出方块边缘；法环采样地形的边缘仍需留意。',
            'nofall':'保护 Minecraft 坠落伤害；无法阻止法环必死区域。',
            'autotool':'自动选择背包里合适的工具，不生成新物品。',
            'autoarmor':'自动整理 Minecraft 护甲，与法环装备独立。满包时原模块可能丢出旧护甲。',
            'esp':'用方框显示已桥接的敌人。',
            'tracers':'显示指向敌人的方向线。'}[key]


def content(data):
    definitions, skills, connections = {}, {}, []
    groups = list(data['categories'])
    for group_index, group in enumerate(groups):
        members = [a for a in data['abilities'] if a['category'] == group]
        # Six radial regions, with base abilities inward and mastery outward.
        # Connections still mean real prerequisites; visual grouping grants no new skill.
        bearing = -90 + group_index * 60
        for column, ability in enumerate(members):
            spread = min(42, (len(members) - 1) * 19)
            angle = bearing + (column / (len(members) - 1) - .5) * spread if len(members) > 1 else bearing
            for index, rank in enumerate(ability['ranks']):
                stage = f"；先在本树投入 {rank['requiredSpent']} 点" if rank['requiredSpent'] else ''
                tier = ['入门', '熟练', '精通'][index] if len(ability['ranks']) > 1 else '技艺'
                definitions[rank['node']] = {
                    'title': {'text': f"{ability['name']} · {tier}", 'color': COLORS[group_index]},
                    'description': f"{data['categories'][group]}路线 · 消耗 {rank['cost']} 点{stage}\n{effect_text(ability, rank, index)}\n学习后在 ClickGUI 中自行开启。",
                    'icon': {'type': 'item', 'data': {'item': ability['icon']}},
                    'frame': ['task', 'goal', 'challenge'][index],
                    'size': 1.05 + index * .12,
                    'cost': rank['cost'],
                    'required_skills': max(1, len(rank['prerequisites'])),
                    'required_spent_points': rank['requiredSpent'],
                }
                radius = 150 + index * 64
                skills[rank['node']] = {'x': round(math.cos(math.radians(angle)) * radius),
                                        'y': round(math.sin(math.radians(angle)) * radius),
                                        'definition': rank['node'], 'root': not rank['prerequisites']}
                connections.extend([[previous, rank['node']] for previous in rank['prerequisites']])
    return {'definitions.json': definitions, 'skills.json': skills,
            'connections.json': {'normal': {'unidirectional': connections}},
            'category.json': {
                'title': 'ER Bridge · 交界地成长',
                'description': '机动 / 战斗 / 建造 / 生存 / 情报 / 工具。真实升级每级一点；所有栏目共用同一余额。76 点广修，116 点广修加两项精通，满配 232 点。',
                'icon': {'type': 'item', 'data': {'item': 'minecraft:nether_star'}},
                'background': 'minecraft:textures/block/black_concrete.png',
                'unlocked_by_default': False, 'starting_points': 0, 'exclusive_root': False, 'erase_on_death': False,
            }}


def preview(data, tree):
    """A repo-native vector diagram of the shipped coordinates, not a game screenshot."""
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000" viewBox="-390 -410 780 800">',
             '<rect x="-390" y="-410" width="780" height="800" fill="#111a21"/>',
             '<g font-family="Microsoft YaHei, sans-serif" fill="#e8ece9" text-anchor="middle">',
             '<text y="-375" font-size="23">交界地成长 · 六类布局</text>',
             '<text y="-349" font-size="12" fill="#a8b8bd">当前 29 节点布局预览 · 非游戏截图 · 连线表示真实前置</text>']
    for left, right in tree['connections.json']['normal']['unidirectional']:
        a, b = tree['skills.json'][left], tree['skills.json'][right]
        parts.append(f'<path d="M{a["x"]},{a["y"]} L{b["x"]},{b["y"]}" stroke="#65767e" stroke-width="2"/>')
    for i, group in enumerate(data['categories']):
        angle = math.radians(-90 + i * 60)
        x, y = round(87 * math.cos(angle)), round(87 * math.sin(angle))
        parts.append(f'<text x="{x}" y="{y}" font-size="15" fill="{COLORS[i]}">{escape(data["categories"][group])}</text>')
        for ability in (a for a in data['abilities'] if a['category'] == group):
            for n, rank in enumerate(ability['ranks']):
                p = tree['skills.json'][rank['node']];x,y=p['x'],p['y']
                short = {'fly':'飞行','airjump':'空跳','scaffold':'搭桥','safewalk':'护边','nofall':'落地',
                         'velocity':'抗击退','aura':'近战','autotool':'工具','autoarmor':'护甲',
                         'esp':'轮廓','nametags':'标记','tracers':'指向'}.get(ability['id'], ability['name'])
                parts.append(f'<g><title>{escape(ability["name"])} {n+1} · {rank["cost"]} 点</title>')
                parts.append(f'<circle cx="{x}" cy="{y}" r="{14+n*2}" fill="#1d2931" stroke="{COLORS[i]}" stroke-width="2"/>')
                parts.append(f'<text x="{x}" y="{y+4}" font-size="11">{rank["cost"]}</text>')
                parts.append(f'<text x="{x}" y="{y+32+n*2}" font-size="10" fill="{COLORS[i]}">{escape(short)} {n+1}</text></g>')
    parts += ['<text y="2" font-size="14">76 点基础</text>', '<text y="22" font-size="12" fill="#a8b8bd">116 点广修双专精</text>',
              '<text y="354" font-size="12" fill="#a8b8bd">本批保留既有节点、价格与前置；玩法分支候选另见 growth-directions.md</text>', '</g></svg>']
    return '\n'.join(parts) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    data = json.loads(CATALOG.read_text(encoding='utf8'))
    tree = content(data)
    for name, value in tree.items():
        serialized = json.dumps(value, ensure_ascii=False, indent=2) + '\n'
        path = TREE / name
        if args.check:
            if path.read_text(encoding='utf8') != serialized:
                raise SystemExit(f'Generated Skills tree is stale: {path}; run this tool without --check')
        else:
            path.write_text(serialized, encoding='utf8')
    svg = preview(data, tree)
    if args.check:
        if not PREVIEW.exists() or PREVIEW.read_text(encoding='utf8') != svg:
            raise SystemExit('Generated layout preview is stale')
    else:
        PREVIEW.parent.mkdir(parents=True, exist_ok=True)
        PREVIEW.write_text(svg, encoding='utf8')
    basic = sum(a['ranks'][0]['cost'] for a in data['abilities'])
    all_points = sum(r['cost'] for a in data['abilities'] for r in a['ranks'])
    print(f"Catalog: {len(data['abilities'])} abilities / {sum(len(a['ranks']) for a in data['abilities'])} nodes; basic={basic}, full={all_points}")
    for preset in data['presets']:
        cost = sum(sum(r['cost'] for r in a['ranks'][:preset['ranks'].get(a['id'], 0)]) for a in data['abilities'])
        assert cost <= preset['budget']
        print(f"Preset {preset['id']}: cost={cost}, test budget={preset['budget']}")


if __name__ == '__main__':
    main()
