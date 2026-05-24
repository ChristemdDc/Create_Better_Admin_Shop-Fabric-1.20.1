import json

with open(r'f:\Proyectos Visual Studio\Mods Minecraft\Create_Better_Admin_Shop-Fabric-1.20.1\src\main\resources\assets\betteradminshop\models\block\shop_block.json', 'r') as f:
    m = json.load(f)

groups = m.get('groups', [])

def find_group(groups, name):
    for g in groups:
        if isinstance(g, dict):
            if g.get('name') == name:
                return g
            sub = find_group(g.get('children', []), name)
            if sub:
                return sub
    return None

def get_bounds(m, group):
    children = [i for i in group.get('children', []) if isinstance(i, int)]
    if not children:
        return None, None
    all_from = []
    all_to = []
    for idx in children:
        el = m['elements'][idx]
        all_from.append(el['from'])
        all_to.append(el['to'])
    mins = [min(f[i] for f in all_from) for i in range(3)]
    maxs = [max(t[i] for t in all_to) for i in range(3)]
    return mins, maxs

e = find_group(groups, 'entrega')
c = find_group(groups, 'confirmarcompra')

print("=== entrega ===")
print(e)
if e:
    mins, maxs = get_bounds(m, e)
    print("AABB from:", mins, "to:", maxs)
    children = [i for i in e.get('children', []) if isinstance(i, int)]
    for idx in children:
        el = m['elements'][idx]
        print("  elem", idx, "from", el['from'], "to", el['to'])

print()
print("=== confirmarcompra ===")
print(c)
if c:
    mins, maxs = get_bounds(m, c)
    print("AABB from:", mins, "to:", maxs)
    children_c = [i for i in c.get('children', []) if isinstance(i, int)]
    for idx in children_c:
        el = m['elements'][idx]
        print("  elem", idx, "from", el['from'], "to", el['to'])

print()
print("=== all top-level groups ===")
for g in groups:
    if isinstance(g, dict):
        print(g.get('name'), '- children count:', len(g.get('children', [])))
