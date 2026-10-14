"""Compile the shipped catalog/recovery journal and check budgets, stages and durable recovery metadata."""
from pathlib import Path
import os
import subprocess
import tempfile
from test_b01_preparation import java_path

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT/'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge'
CHARACTER = ROOT/'bridge-base/elden-ring/mc-bridge/character-bridge/src'

JAVA = r'''
import com.google.gson.*;
import dev.ermc.bridge.api.*;
import dev.ermc.bridge.api.CapabilitySnapshot.*;
import dev.ermc.bridge.gameplay.capability.*;
import dev.ermc.bridge.gameplay.skills.GrowthStateStore;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public class GrowthCatalogCheck {
    interface Action { void run() throws Exception; }
    static int checks;
    static void check(boolean b, String m) { if(!b) throw new AssertionError(m); checks++; }
    static void reject(Action action) throws Exception { try { action.run(); } catch (Exception e) {checks++;return;} throw new AssertionError("Accepted invalid data"); }
    public static void main(String[] args) throws Exception {
        AbilityCatalog c=AbilityCatalog.instance();
        check(c.entries().size()==15 && c.nodes().size()==30 && c.categories().size()==6,"catalog counts");
        Map<Ability,Integer> basic=new HashMap<>(),full=new HashMap<>();
        c.entries().forEach(e->{if(e.branchOf()==null)basic.put(e.id(),1);full.put(e.id(),e.ranks().size());});
        check(c.cost(basic)==76,"all foundations budget");check(c.cost(full)==238,"full budget includes optional hop route");
        check(c.cost(c.presets().get("journey").ranks())==116,"all foundations plus two masteries");
        var air=c.entry(new Ability("airjump"));
        check(air.ranks().get(0).settings().get("Mode").stringValue().equals("DOUBLE_JUMP"),"initial safe jump mode");
        var modes=air.ranks().get(1).settings().get("Mode");
        check(modes.type().equals("choice_set") && modes.allowedChoices().equals(Set.of("DOUBLE_JUMP","JUMP_FREELY"))
                && modes.stringValue().equals("DOUBLE_JUMP"),"upgrade expands allowed choices with explicit safe fallback");
        reject(()->new AbilityCatalog.Setting("choice_set","Mode",null,"GHOST_BLOCK",null,Set.of("DOUBLE_JUMP","JUMP_FREELY")));
        for(var p:c.presets().values()) {
            Set<String> nodes=c.selectedNodes(p.ranks()); List<String> order=c.purchaseOrder(nodes);
            check(new HashSet<>(order).equals(nodes),"complete preset order");
            check(c.cost(p.ranks())==p.budget(),"exact preset budget");
            check(c.ranks(nodes).entrySet().stream().allMatch(e->e.getValue().equals(p.ranks().getOrDefault(e.getKey(),0))),"rank reconstruction");
        }
        reject(()->c.selectedNodes(Map.of(new Ability("fly"),1)));
        reject(()->c.ranks(Set.of("airjump_2")));
        reject(()->c.ranks(Set.of("unknown_1")));
        reject(()->c.entry(new Ability("timer")));
        reject(()->new Ability("SPEED"));
        var binding=new CharacterBinding(new UUID(0,1),new UUID(0,2),new UUID(0,3));
        var locked=c.policy(3,true).snapshot(binding,Map.of());
        check(locked.abilities().values().stream().noneMatch(Grant::unlocked),"everything explicitly locked");
        var learned=c.policy(3,true).snapshot(binding,c.presets().get("journey").ranks());
        check(learned.abilities().get(Ability.SPEED).rank()==3,"preset policy");
        var speed=c.entry(Ability.SPEED);
        var oldBuild=c.policy(3,true).snapshot(binding,Map.of(Ability.SPEED,2));
        check(c.cost(Map.of(Ability.SPEED,2))==8 && c.ranks(Set.of("speed_1","speed_2")).get(Ability.SPEED)==2,"existing Speed II unchanged: nine earned leaves one point");
        var baseOnly=ModuleCapability.resolve(speed,oldBuild);
        check(baseOnly.settings().get("Mode").stringValue().equals("Custom")&&baseOnly.branches().isEmpty(),"existing learning does not grant hop");
        var dual=c.policy(3,true).snapshot(binding,c.presets().get("speed_routes").ranks());
        var routes=ModuleCapability.resolve(speed,dual);
        check(routes.settings().get("Mode").allowedChoices().equals(Set.of("Custom","LegitHop")),"independently learned mode union");
        check(routes.limits().get("speed").maximum()==.33,"hop cannot increase ground cap");
        check(ModuleCapability.resolve(speed,c.policy(3,true).snapshot(binding,Map.of(new Ability("speed_hop"),1))).branches().isEmpty(),"branch cannot grant without base");
        reject(()->c.selectedNodes(Map.of(new Ability("speed_hop"),1)));
        check(ModuleCapability.resolve(speed,oldBuild).settings().get("Mode").type().equals("choice"),"refund projects single safe mode");
        reject(()->learned.authorize(new Ability("timer"),3,"default",Map.of()));
        reject(()->c.entries().clear());reject(()->c.nodes().clear());
        String json=Files.readString(Path.of(args[0]));
        JsonObject malformed=JsonParser.parseString(json).getAsJsonObject();malformed.addProperty("unexpected",1);
        String unknownField=malformed.toString();reject(()->AbilityCatalog.parse(new StringReader(unknownField)));
        malformed=JsonParser.parseString(json).getAsJsonObject();malformed.addProperty("version",99);
        String wrongVersion=malformed.toString();reject(()->AbilityCatalog.parse(new StringReader(wrongVersion)));
        GrowthStateStore store=new GrowthStateStore(Path.of(args[1]));
        var state=new GrowthStateStore.State(binding,2,"preset_applying","starter",14,Set.of("speed_1"),Set.of("aura_1"));
        store.save(state);check(store.load(binding).orElseThrow().equals(state),"pending recovery persists before Skills mutation");
        store.save(state.phase("preset_active"));check(store.load(binding).orElseThrow().testActive(),"active source persists");
        store.save(state.phase("preset_clearing"));check(store.load(binding).orElseThrow().backupNodes().equals(Set.of("speed_1")),"clear retains original build");
        store.save(state.ready());check(store.load(binding).orElseThrow().legacyNodes().contains("aura_1"),"migration rollback list survives test cleanup");
        reject(()->store.load(new CharacterBinding(binding.playerId(),binding.campaignId(),UUID.randomUUID())));
        Files.writeString(store.path(binding),Files.readString(store.path(binding))+"unknown=x\n");reject(()->store.load(binding));
        check(Files.readString(store.path(binding)).contains("unknown=x"),"corrupt journal not silently reset");
        System.out.println("PASS: "+checks+" actual catalog/policy/journal checks; 76 foundations, 116 broad+two masteries, 238 including hop; no games launched");
    }
}
'''

def main():
    java=java_path(None)
    gson=next((ROOT/'.tools/gradle-home/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1').glob('*/*.jar'))
    resources=CHARACTER/'main/resources'
    with tempfile.TemporaryDirectory(prefix='ermc-growth-') as directory:
        work=Path(directory);fixture=work/'GrowthCatalogCheck.java';fixture.write_text(JAVA,encoding='utf8')
        sources=[CORE/'api/CharacterBinding.java',CORE/'api/CapabilitySnapshot.java',
                 *sorted((CHARACTER/'main/java/dev/ermc/bridge/gameplay/capability').glob('*.java')),
                 CHARACTER/'main/java/dev/ermc/bridge/gameplay/skills/GrowthStateStore.java',fixture]
        subprocess.run([java,'-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',str(gson),'-d',str(work),*map(str,sources)],check=True,cwd=ROOT)
        subprocess.run([java,'-cp',os.pathsep.join(map(str,[work,gson,resources])),'GrowthCatalogCheck',str(resources/'data/erbridge/ability_catalog.json'),str(work/'journal')],check=True,cwd=ROOT)

if __name__=='__main__':main()
