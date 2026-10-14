"""Generate the actual Skills tree from the shared growth catalog; --check rejects drift."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHARACTER = ROOT / 'bridge-base/elden-ring/mc-bridge/character-bridge'
CATALOG = CHARACTER / 'src/main/resources/data/erbridge/ability_catalog.json'
TREE = CHARACTER / 'src/skills/resources/data/erbridge/puffish_skills/categories/abilities'


def content(data):
    definitions, skills, connections = {}, {}, []
    groups = list(data['categories'])
    for group_index, group in enumerate(groups):
        members = [a for a in data['abilities'] if a['category'] == group]
        # Six separated panels inside one shared-balance Skills category.
        base_x, base_y = (group_index % 3) * 330 - 330, (group_index // 3) * 250
        for column, ability in enumerate(members):
            for index, rank in enumerate(ability['ranks']):
                stage = f"；先在本树投入 {rank['requiredSpent']} 点" if rank['requiredSpent'] else ''
                limits = '、'.join(f'{key} {value[0]}–{value[1]}' for key, value in rank['limits'].items()
                                  if value[0] != value[1] and key not in ('cooldown', 'jumpOrder'))
                definitions[rank['node']] = {
                    'title': f"{data['categories'][group]} · {ability['name']} {index + 1}",
                    'description': f"{ability['description']}\n消耗 {rank['cost']} 点{stage}。{limits}\n学习不会自动启用模块。",
                    'icon': {'type': 'texture', 'data': {'texture': ability['icon']}},
                    'cost': rank['cost'],
                    'required_skills': max(1, len(rank['prerequisites'])),
                    'required_spent_points': rank['requiredSpent'],
                }
                skills[rank['node']] = {'x': base_x + column * 70, 'y': base_y - index * 50,
                                        'definition': rank['node'], 'root': not rank['prerequisites']}
                connections.extend([[previous, rank['node']] for previous in rank['prerequisites']])
    return {'definitions.json': definitions, 'skills.json': skills,
            'connections.json': {'normal': {'unidirectional': connections}},
            'category.json': {
                'title': 'ER Bridge · 交界地成长',
                'description': '机动 / 战斗 / 建造 / 生存 / 情报 / 工具。真实升级每级一点；所有栏目共用同一余额。76 点广修，116 点广修加两项精通，满配 232 点。',
                'icon': {'type': 'texture', 'data': {'texture': 'minecraft:textures/item/nether_star.png'}},
                'background': 'minecraft:textures/block/deepslate_tiles.png',
                'unlocked_by_default': False, 'starting_points': 0, 'exclusive_root': False, 'erase_on_death': False,
            }}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    data = json.loads(CATALOG.read_text(encoding='utf8'))
    for name, value in content(data).items():
        serialized = json.dumps(value, ensure_ascii=False, indent=2) + '\n'
        path = TREE / name
        if args.check:
            if path.read_text(encoding='utf8') != serialized:
                raise SystemExit(f'Generated Skills tree is stale: {path}; run this tool without --check')
        else:
            path.write_text(serialized, encoding='utf8')
    basic = sum(a['ranks'][0]['cost'] for a in data['abilities'])
    all_points = sum(r['cost'] for a in data['abilities'] for r in a['ranks'])
    print(f"Catalog: {len(data['abilities'])} abilities / {sum(len(a['ranks']) for a in data['abilities'])} nodes; basic={basic}, full={all_points}")
    for preset in data['presets']:
        cost = sum(sum(r['cost'] for r in a['ranks'][:preset['ranks'].get(a['id'], 0)]) for a in data['abilities'])
        assert cost <= preset['budget']
        print(f"Preset {preset['id']}: cost={cost}, test budget={preset['budget']}")


if __name__ == '__main__':
    main()
