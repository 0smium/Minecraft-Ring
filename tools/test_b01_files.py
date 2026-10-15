"""Compile the actual B01Files/API/policy and exercise real local filesystem persistence.

No Fabric, game process or production mocks. Uses the runtime's single-writer contract.
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile
from test_b01_preparation import java_path, DOMAIN_API

ROOT=Path(__file__).resolve().parents[1]
COMMON=ROOT/'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge'
CHARACTER=ROOT/'bridge-base/elden-ring/mc-bridge/character-bridge/src/main/java/dev/ermc/bridge'

FIXTURE=r'''
import dev.ermc.bridge.api.*;
import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.gameplay.runtime.B01Files;
import dev.ermc.bridge.gameplay.runtime.B01Files.Binding;
import dev.ermc.bridge.gameplay.runtime.B01Files.Settings;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class B01FilesCheck {
    interface Action {void run() throws Exception;}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    static void io(Action action,String label)throws Exception {
        try{action.run();}catch(IOException expected){return;}throw new AssertionError("Accepted "+label);
    }
    static void invalid(Action action,String label)throws Exception {
        try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError("Accepted "+label);
    }
    static Properties properties(Path file)throws Exception {
        Properties p=new Properties();try(var r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(r);}return p;
    }
    static void writeProperties(Path file,Properties p)throws Exception {
        try(var writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){p.store(writer,"fixture corruption");}
    }
    static Settings settings(UUID campaign,long revision,List<Double> speed,List<Double> aura){
        return new Settings(campaign,false,revision,1,0,true,speed,aura);
    }
    static void noTemporary(Path directory)throws Exception {
        try(var files=Files.list(directory)){check(files.noneMatch(p->p.getFileName().toString().startsWith(".b01-")),"temporary file leaked");}
    }
    public static void main(String[] args)throws Exception {
        Path data=Path.of(args[0]);
        B01Files files=new B01Files(data.resolve("temporary/../world \u4e16\u754c"));
        if(args.length>1){restore(data,files);return;}
        check(files.root().isAbsolute()&&files.root().equals(files.root().normalize()),"root normalization");
        io(()->files.loadSettings(false),"missing configuration without create");
        Settings initial=files.loadSettings(true);
        check(initial.revision()==1&&!initial.challenge()&&initial.pointsPerLevel()==1&&initial.startingPoints()==0,"default settings");
        check(files.loadSettings(true).equals(initial),"existing campaign was recreated");
        check(new B01Files(files.root()).loadSettings(false).equals(initial),"configuration did not persist");
        List<Double> speed=new ArrayList<>(List.of(.30,.33,.36));
        Settings copied=settings(initial.campaign(),2,speed,List.of(2.5,3.,3.5));speed.set(0,.59);
        check(copied.speed().get(0)==.30,"configuration retained mutable input");
        invalid(()->copied.speed().set(0,.59),"mutable returned caps");
        for(List<Double> caps:List.of(List.of(.3,.2,.4),List.of(.01,.2,.4),List.of(.3,.4,.7),List.of(.3,.4),List.of(.3,.4,Double.NaN),List.of(.3,.4,Double.POSITIVE_INFINITY)))
            invalid(()->settings(initial.campaign(),1,caps,List.of(2.5,3.,3.5)),"invalid Speed caps");
        for(List<Double> caps:List.of(List.of(2.5,2.,3.5),List.of(.5,2.,3.5),List.of(2.5,3.,6.1),List.of(2.5,3.,Double.NEGATIVE_INFINITY)))
            invalid(()->settings(initial.campaign(),1,List.of(.3,.33,.36),caps),"invalid Aura caps");
        invalid(()->settings(initial.campaign(),0,List.of(.3,.33,.36),List.of(2.5,3.,3.5)),"revision zero");
        invalid(()->new Settings(initial.campaign(),false,1,0,0,true,initial.speed(),initial.aura()),"point rate zero");
        invalid(()->new Settings(initial.campaign(),false,1,101,0,true,initial.speed(),initial.aura()),"point rate above limit");
        invalid(()->new Settings(initial.campaign(),false,1,1,-1,true,initial.speed(),initial.aura()),"negative initial points");
        invalid(()->new Settings(initial.campaign(),false,1,1,1001,true,initial.speed(),initial.aura()),"initial points above limit");
        invalid(()->settings(initial.campaign(),Long.MAX_VALUE,initial.speed(),initial.aura()).mode(true),"revision overflow");
        Settings changed=initial.mode(true);check(changed.challenge()&&changed.revision()==2&&initial.revision()==1,"mode revision mutation");
        UUID owner=UUID.randomUUID(),character=UUID.randomUUID();
        CharacterBinding identity=new CharacterBinding(owner,initial.campaign(),character);
        var view=changed.policy().snapshot(identity,Map.of(Ability.SPEED,2,Ability.AURA,3));
        check(view.policyRevision()==2&&view.abilities().get(Ability.SPEED).numericLimits().get("speed").maximum()==.33,"Speed policy cap/revision");
        check(view.abilities().get(Ability.AURA).numericLimits().get("range").maximum()==3.5,"Aura policy cap");
        invalid(()->view.authorize(Ability.SPEED,1,"ground_custom",Map.of("speed",.3)),"stale revision");
        invalid(()->view.authorize(Ability.SPEED,2,"other",Map.of("speed",.3)),"unknown mode");
        invalid(()->view.authorize(Ability.SPEED,2,"ground_custom",Map.of("speed",Double.NaN)),"nonfinite request");
        invalid(()->changed.policy().snapshot(identity,Map.of()).authorize(Ability.AURA,2,"single_target",Map.of("range",3.)),"locked ability");
        Binding binding=new Binding(identity,-1L,"\u6d4b\u8bd5\ud83d\ude00");
        files.createBinding(binding);
        check(new B01Files(files.root()).loadBinding(owner,initial.campaign()).orElseThrow().equals(binding),"Unicode/unsigned fingerprint persistence");
        check(binding.matches(-1L,binding.observedName())&&!binding.matches(1L,binding.observedName()),"observation matching");
        Path bindingPath=files.root().resolve("bindings").resolve(owner+".properties");
        byte[] originalBinding=Files.readAllBytes(bindingPath);
        io(()->files.createBinding(new Binding(new CharacterBinding(owner,initial.campaign(),UUID.randomUUID()),2,"replacement")),"old binding overwrite");
        io(()->files.createBinding(new Binding(new CharacterBinding(owner,UUID.randomUUID(),UUID.randomUUID()),2,"replacement")),"campaign rebind overwrite");
        check(Arrays.equals(originalBinding,Files.readAllBytes(bindingPath)),"rejected binding was rewritten");
        io(()->files.loadBinding(owner,UUID.randomUUID()),"campaign mismatch");
        check(files.loadBinding(UUID.randomUUID(),initial.campaign()).isEmpty(),"other owner's binding leaked");
        Properties badBinding=properties(bindingPath);badBinding.setProperty("player",UUID.randomUUID().toString());writeProperties(bindingPath,badBinding);
        io(()->files.loadBinding(owner,initial.campaign()),"owner mismatch");Files.write(bindingPath,originalBinding);
        byte[] originalConfig=Files.readAllBytes(files.configPath());
        for(String[] mutation:new String[][]{{"version","2"},{"challenge","TRUE"},{"revision","0"},{"pointsPerLevel","101"},{"startingPoints","-1"},{"speed",".3,.2,.4"},{"aura","2.5,3,NaN"},{"campaign","not-a-uuid"},{"unknown","value"}}){
            Properties malformed=properties(files.configPath());malformed.setProperty(mutation[0],mutation[1]);writeProperties(files.configPath(),malformed);
            byte[] corrupt=Files.readAllBytes(files.configPath());io(()->files.loadSettings(true),"malformed config "+mutation[0]);
            check(Arrays.equals(corrupt,Files.readAllBytes(files.configPath())),"invalid config was reset/recreated");Files.write(files.configPath(),originalConfig);
        }
        Properties missing=properties(files.configPath());missing.remove("aura");writeProperties(files.configPath(),missing);io(()->files.loadSettings(true),"missing field");
        Files.write(files.configPath(),originalConfig);Files.writeString(files.configPath(),"\\uZZZZ=broken",StandardCharsets.UTF_8);io(()->files.loadSettings(true),"malformed properties escape");
        Files.writeString(files.configPath(),"x".repeat(8193),StandardCharsets.UTF_8);io(()->files.loadSettings(true),"oversized config");Files.write(files.configPath(),originalConfig);
        // Runtime performs all IO on its integrated-server thread, protected by the world session lock.
        Settings a=initial.revision(10),b=new Settings(initial.campaign(),true,11,2,1,false,List.of(.2,.22,.24),List.of(1.5,2.,2.5));
        for(int i=0;i<40;i++){
            Settings expected=i%2==0?b:a;files.saveSettings(expected);
            check(new B01Files(files.root()).loadSettings(false).equals(expected),"partial/hybrid saved config");
        }
        noTemporary(files.root());
        B01Files unwritable=new B01Files(data.resolve("move-failure"));Files.createDirectories(unwritable.configPath());
        io(()->unwritable.saveSettings(initial),"atomic rename to a directory");check(Files.isDirectory(unwritable.configPath()),"failed write destroyed target");noTemporary(unwritable.root());
        Properties manifest=new Properties();manifest.setProperty("campaign",initial.campaign().toString());manifest.setProperty("owner",owner.toString());manifest.setProperty("character",character.toString());
        writeProperties(data.resolve("restore.properties"),manifest);
        System.out.println("PASS: actual B01Files local Unicode roundtrip, binding/campaign/owner protection, strict config, finite monotonic caps, revision/policy, 40 complete atomic saves and failed-write cleanup");
    }
    static void restore(Path data,B01Files files)throws Exception {
        Properties manifest=properties(data.resolve("restore.properties"));UUID campaign=UUID.fromString(manifest.getProperty("campaign")),owner=UUID.fromString(manifest.getProperty("owner"));
        Settings saved=files.loadSettings(true);check(saved.campaign().equals(campaign)&&saved.revision()==10&&!saved.challenge(),"fresh JVM changed persisted campaign/config");
        Binding persisted=files.loadBinding(owner,campaign).orElseThrow();
        check(persisted.identity().characterId().equals(UUID.fromString(manifest.getProperty("character")))&&persisted.weakFingerprint()==-1L&&persisted.observedName().equals("\u6d4b\u8bd5\ud83d\ude00"),"fresh JVM lost binding identity/observation");
        byte[] prior=Files.readAllBytes(files.root().resolve("bindings").resolve(owner+".properties"));
        io(()->files.createBinding(new Binding(new CharacterBinding(owner,campaign,UUID.randomUUID()),2,"replacement")),"fresh JVM duplicate binding");
        check(Arrays.equals(prior,Files.readAllBytes(files.root().resolve("bindings").resolve(owner+".properties"))),"fresh JVM duplicate replaced persisted binding");
        noTemporary(files.root());noTemporary(files.root().resolve("bindings"));
        System.out.println("PASS: separate JVM restored campaign, revision, persistent binding UUID and Unicode/unsigned observation; old binding remains protected");
    }
}
'''

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',default=os.environ.get('JAVA_HOME'))
    args=parser.parse_args();java=java_path(args.java_home)
    gson=next((ROOT/'.tools/gradle-home/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1').glob('*/*.jar'))
    resources=ROOT/'bridge-base/elden-ring/mc-bridge/character-bridge/src/main/resources'
    with tempfile.TemporaryDirectory(prefix='ermc-b01-files-') as directory:
        work=Path(directory);fixture=work/'B01FilesCheck.java';fixture.write_text(FIXTURE,encoding='utf8')
        classes=work/'classes';classes.mkdir()
        sources=[COMMON/'api'/f'{name}.java' for name in DOMAIN_API]+sorted((CHARACTER/'gameplay/capability').glob('*.java'))
        sources += [CHARACTER/'gameplay/runtime/B01Files.java',fixture]
        subprocess.run([java,'-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-Xlint:all','-Werror','-cp',str(gson),'-d',str(classes),*map(str,sources)],check=True)
        command=[java,'-cp',os.pathsep.join(map(str,[classes,gson,resources])),'B01FilesCheck',str(work/'data')]
        subprocess.run(command,check=True)
        subprocess.run(command+['--restore'],check=True)

if __name__=='__main__':main()
