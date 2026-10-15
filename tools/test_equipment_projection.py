"""Real MC stack/slot projection safety check, with no game launch or equipment write."""
from pathlib import Path
import os
from test_lb_capability_adapter import single, invoke
ROOT=Path(__file__).resolve().parents[1]
PROJECT=ROOT/'bridge-base/elden-ring/mc-bridge'
OUTPUT=ROOT/'runtime/experiments/equipment-projection-check'
def main():
    OUTPUT.mkdir(parents=True,exist_ok=True);classes=OUTPUT/'classes';classes.mkdir(exist_ok=True)
    cache=ROOT/'.tools/gradle-home/caches'
    deps=[single((PROJECT/'.gradle/loom-cache/minecraftMaven/net/minecraft').glob(f'minecraft-{part}-*/1.21.1-loom.*/minecraft-{part}-*.jar'),f'widened {part}') for part in ('common','clientOnly')]
    deps+=sorted(p for p in (PROJECT/'.gradle/loom-cache/remapped_mods').rglob('*.jar') if not any(s in p.name for s in ('-sources','-javadoc')))
    deps+=sorted(p for p in (cache/'modules-2/files-2.1').rglob('*.jar') if not any(s in p.name for s in ('-sources','-javadoc','-natives-')))
    cp=os.pathsep.join(map(str,[classes,PROJECT/'build/classes/java/main',PROJECT/'build/classes/java/client',PROJECT/'character-bridge/build/classes/java/main',PROJECT/'character-bridge/build/classes/java/client',*deps]))
    java=ROOT/'.tools/java/jdk-25.0.4.1+1/bin/java.exe'
    fixture=PROJECT/'src/testFixtures/equipment/EquipmentProjectionCheck.java'
    invoke(java,['-m','jdk.compiler/com.sun.tools.javac.Main','-proc:none','-encoding','UTF-8','-classpath',cp,'-d',classes,fixture],OUTPUT/'compile.log')
    invoke(java,['-classpath',cp,'dev.ermc.bridge.character.equipment.EquipmentProjectionCheck'],OUTPUT/'projection.log')
    print((OUTPUT/'projection.log').read_text(encoding='utf-8',errors='replace'))
if __name__=='__main__':main()
