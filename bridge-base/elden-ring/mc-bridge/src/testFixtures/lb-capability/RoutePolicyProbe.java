import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import dev.ermc.bridge.api.*;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog;
import com.google.gson.*;

/** Real transformed LB choice/lifecycle plus an explicit module-enabled sink; never starts MC. */
public final class RoutePolicyProbe {
    static int checks,stops;static Object enabled,mode;static Class<?> choiceType;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static CapabilitySnapshot policy(boolean hop,long revision){
        var base=RuntimeValueProbe.snapshot(2,revision);var grants=new HashMap<>(base.abilities());
        if(hop){var entry=AbilityCatalog.instance().entry(new CapabilitySnapshot.Ability("speed_hop"));var rank=entry.ranks().get(0);
            grants.put(entry.id(),new CapabilitySnapshot.Grant(1,true,rank.modes(),rank.limits()));}
        return new CapabilitySnapshot(base.binding(),revision,grants);
    }
    public static void moduleEnabled(Object module,boolean state)throws Throwable{
        RuntimeValueProbe.set.invoke(enabled,state);
        choiceType.getMethod("newState",boolean.class).invoke(mode,state);
        if(!state)stops++;
    }
    @SuppressWarnings("unchecked") public static void run(ClassLoader loader)throws Throwable{
        Class<?> fixture=Class.forName("net.ccbluex.liquidbounce.erbridgefixture.FixtureChoice",true,loader);
        Class<?> choice=Class.forName("net.ccbluex.liquidbounce.config.types.Choice",true,loader);
        choiceType=Class.forName("net.ccbluex.liquidbounce.config.types.ChoiceConfigurable",true,loader);
        Class<?> event=Class.forName("net.ccbluex.liquidbounce.event.EventListener",true,loader),function=Class.forName("kotlin.jvm.functions.Function1",true,loader);
        enabled=RuntimeValueProbe.makeValue.newInstance("Enabled",false,RuntimeValueProbe.constant(RuntimeValueProbe.rangeType,"BOOLEAN"),
            RuntimeValueProbe.constant(RuntimeValueProbe.makeValue.getParameterTypes()[3],"None"),false);
        Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{event},(proxy,m,a)->m.getName().equals("getRunning")?RuntimeValueProbe.raw.invoke(enabled):null);
        Object ground=fixture.getConstructor(String.class).newInstance("Custom"),hop=fixture.getConstructor(String.class).newInstance("LegitHop"),yport=fixture.getConstructor(String.class).newInstance("YPort");
        Object options=Array.newInstance(choice,3);Array.set(options,0,ground);Array.set(options,1,hop);Array.set(options,2,yport);
        Object initial=Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,m,a)->ground);
        Object alternatives=Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,m,a)->options);
        mode=choiceType.getConstructor(event,String.class,function,function).newInstance(listener,"Mode",initial,alternatives);
        for(Object c:List.of(ground,hop,yport))fixture.getField("owner").set(c,mode);
        RuntimeValueProbe.rules.clear();RuntimeValueProbe.bindings.clear();
        Object binding=RuntimeValueProbe.bind("Speed","speed",new ArrayList<>(List.of(mode,enabled)));
        RuntimeValueProbe.rule(binding,mode,"Mode",false,null);RuntimeValueProbe.rule(binding,enabled,"Enabled",true,null);
        Object access=RuntimeValueProbe.field(RuntimeValueProbe.helper,"access").get(null);
        MethodHandle makeFloat=MethodHandles.publicLookup().findStatic(Class.forName("kotlin.ranges.RangesKt",false,loader),"rangeTo",
            MethodType.methodType(Class.forName("kotlin.ranges.ClosedFloatingPointRange",false,loader),float.class,float.class));
        var base=AbilityCatalog.instance().entry(CapabilitySnapshot.Ability.SPEED);
        for(var rank:base.ranks())for(var setting:rank.settings().values()){
            if(setting.path().equals("Mode"))continue;
            Map<String,Object> values=(Map<String,Object>)RuntimeValueProbe.field(RuntimeValueProbe.binding,"values").get(binding);
            if(values.containsKey(setting.path()))continue;
            Object value,range=null;String name=setting.path().substring(setting.path().lastIndexOf('/')+1);
            if(setting.type().equals("boolean"))value=RuntimeValueProbe.makeValue.newInstance(name,setting.booleanValue(),RuntimeValueProbe.constant(RuntimeValueProbe.rangeType,"BOOLEAN"),
                    RuntimeValueProbe.constant(RuntimeValueProbe.makeValue.getParameterTypes()[3],"None"),false);
            else {range=makeFloat.invoke(0F,10F);value=RuntimeValueProbe.ranged(name,1F,"FLOAT",range);}
            RuntimeValueProbe.rule(binding,value,setting.path(),false,range);
        }
        RuntimeValueProbe.field(RuntimeValueProbe.helper,"rules").set(null,RuntimeValueProbe.rules);
        RuntimeValueProbe.field(RuntimeValueProbe.helper,"bindings").set(null,RuntimeValueProbe.bindings);
        Method select=choiceType.getMethod("setByString",String.class),get=choiceType.getMethod("getActiveChoice");
        Method bhop=RuntimeValueProbe.helper.getMethod("allowBHop",Object.class);
        RuntimeValueProbe.publish.invoke(null,true,policy(false,100));
        RuntimeValueProbe.set.invoke(enabled,true);
        select.invoke(mode,"LegitHop");check(get.invoke(mode)==ground,"unlearned hop stays ground");
        check(!(boolean)bhop.invoke(null,ground)&&!(boolean)bhop.invoke(null,hop),"ground keeps manual jump");
        RuntimeValueProbe.publish.invoke(null,true,policy(true,101));
        check(get.invoke(mode)==ground,"learning route never auto switches");
        int groundStops=fixture.getField("disables").getInt(ground),hopStarts=fixture.getField("enables").getInt(hop);
        select.invoke(mode,"LegitHop");
        check(get.invoke(mode)==hop&&(boolean)bhop.invoke(null,hop),"learned selected top-level hop runs original jump handler");
        check(fixture.getField("disables").getInt(ground)==groundStops+1&&fixture.getField("enables").getInt(hop)==hopStarts+1,"original setByString stops ground then enables hop");
        check(!(boolean)bhop.invoke(null,ground)&&!(boolean)bhop.invoke(null,yport),"other/nested modes cannot use jump permission");
        int hopStops=fixture.getField("disables").getInt(hop),groundStarts=fixture.getField("enables").getInt(ground);
        select.invoke(mode,"Custom");check(fixture.getField("disables").getInt(hop)==hopStops+1&&fixture.getField("enables").getInt(ground)==groundStarts+1,"reverse original mode lifecycle");
        select.invoke(mode,"YPort");check(get.invoke(mode)==ground,"YPort still unavailable");
        select.invoke(mode,"LegitHop");
        hopStops=fixture.getField("disables").getInt(hop);groundStarts=fixture.getField("enables").getInt(ground);
        // Policy's next reconciliation uses the original raw selection, not its already-filtered getter.
        RuntimeValueProbe.publish.invoke(null,true,policy(false,102));
        check(get.invoke(mode)==ground && !(boolean)bhop.invoke(null,hop),"refund immediately blocks execution reads");
        var enabledHandle=RuntimeValueProbe.field(access.getClass(),"moduleEnabled");Object old=enabledHandle.get(access);
        enabledHandle.set(access,MethodHandles.lookup().findStatic(RoutePolicyProbe.class,"moduleEnabled",MethodType.methodType(void.class,Object.class,boolean.class)));
        try{
            var apply=RuntimeValueProbe.helper.getDeclaredMethod("apply",RuntimeValueProbe.binding);apply.setAccessible(true);apply.invoke(null,binding);
            check(stops==1&&!((Boolean)RuntimeValueProbe.raw.invoke(enabled)),"refund stops module once without re-enabling it");
            check(fixture.getField("disables").getInt(hop)==hopStops+1,"refunded raw hop receives original mode disable callback");
            check(get.invoke(mode)==ground&&fixture.getField("enables").getInt(ground)==groundStarts,"safe base selected with no implicit enable");
        }finally{enabledHandle.set(access,old);}
        // Actual ChoiceConfigurable serializer is shared by FILE/interop: filtering only the response preserves it.
        GsonBuilder gson=new GsonBuilder();
        gson.addSerializationExclusionStrategy((ExclusionStrategy)Class.forName("net.ccbluex.liquidbounce.config.gson.stategies.ProtocolExclusionStrategy",true,loader).getConstructor().newInstance());
        gson.registerTypeHierarchyAdapter(RuntimeValueProbe.configurable,Class.forName("net.ccbluex.liquidbounce.config.gson.serializer.ConfigurableSerializer",true,loader).getConstructor(boolean.class,boolean.class,boolean.class).newInstance(true,true,false));
        gson.registerTypeAdapter(choiceType,Class.forName("net.ccbluex.liquidbounce.config.gson.serializer.ChoiceConfigurableSerializer",true,loader).getField("INSTANCE").get(null));
        gson.registerTypeHierarchyAdapter(Class.forName("kotlin.ranges.ClosedRange",false,loader),
            Class.forName("net.ccbluex.liquidbounce.config.gson.adapter.RangeAdapter",true,loader).getField("INSTANCE").get(null));
        Object fakeModule=RuntimeValueProbe.bindings.keySet().iterator().next();
        RuntimeValueProbe.publish.invoke(null,true,policy(true,103));
        // Restore original choices just as production apply does before it restricts to current learned modes.
        choiceType.getMethod("setChoices",List.class).invoke(mode,new ArrayList<>(List.of(ground,hop,yport)));
        JsonObject json=gson.create().toJsonTree(fakeModule,RuntimeValueProbe.configurable).getAsJsonObject();
        Method gui=Class.forName("dev.ermc.bridge.client.integrations.LiquidBounceGuiPolicy",true,loader).getMethod("settings",JsonElement.class);
        JsonObject shown=(JsonObject)gui.invoke(null,json);
        JsonObject displayed=GuiPolicyProbe.item(shown,"Mode");
        check(displayed!=null&&displayed.getAsJsonObject("choices").keySet().equals(Set.of("Custom","LegitHop")),"real LB mode tree GUI exposes exactly learned routes");
        check(GuiPolicyProbe.item(json,"Mode").getAsJsonObject("choices").has("YPort"),"REST projection never destroys source choices");
        System.out.println("PASS: "+checks+" real LB ChoiceConfigurable mode switching/branch gate/refund/GUI assertions; module-enabled lifecycle sink is an explicit fixture");
        fly(loader);
    }

    static CapabilitySnapshot flyPolicy(int baseRank,int precisionRank,long revision){
        var base=RuntimeValueProbe.snapshot(baseRank,revision);var grants=new HashMap<>(base.abilities());
        var entry=AbilityCatalog.instance().entry(new CapabilitySnapshot.Ability("fly_precision"));
        var rank=entry.ranks().get(Math.max(0,precisionRank-1));
        grants.put(entry.id(),new CapabilitySnapshot.Grant(precisionRank,true,
            precisionRank==0?Set.of():rank.modes(),precisionRank==0?Map.of():rank.limits()));
        return new CapabilitySnapshot(base.binding(),revision,grants);
    }

    /** Actual LB Value/Choice/serializer types; tree shape follows fixed FlyGeneric.kt without starting its singletons. */
    @SuppressWarnings("unchecked") static void fly(ClassLoader loader)throws Throwable{
        int initialChecks=checks,initialGuiChecks=GuiPolicyProbe.checks;stops=0;
        Class<?> fixture=Class.forName("net.ccbluex.liquidbounce.erbridgefixture.FixtureChoice",true,loader);
        Class<?> choice=Class.forName("net.ccbluex.liquidbounce.config.types.Choice",true,loader);
        Class<?> event=Class.forName("net.ccbluex.liquidbounce.event.EventListener",true,loader);
        Class<?> function=Class.forName("kotlin.jvm.functions.Function1",true,loader);
        enabled=bool("Enabled",false);
        Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{event},(proxy,m,a)->m.getName().equals("getRunning")?RuntimeValueProbe.raw.invoke(enabled):null);
        Object creative=fixture.getConstructor(String.class).newInstance("Creative");
        Object precision=fixture.getConstructor(String.class).newInstance("Vanilla");
        Object jetpack=fixture.getConstructor(String.class).newInstance("Jetpack");
        Object options=Array.newInstance(choice,3);Array.set(options,0,creative);Array.set(options,1,precision);Array.set(options,2,jetpack);
        Object initial=Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,m,a)->creative);
        Object alternatives=Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,m,a)->options);
        mode=choiceType.getConstructor(event,String.class,function,function).newInstance(listener,"Mode",initial,alternatives);
        for(Object c:List.of(creative,precision,jetpack))fixture.getField("owner").set(c,mode);
        MethodHandle floatRange=MethodHandles.publicLookup().findStatic(Class.forName("kotlin.ranges.RangesKt",false,loader),"rangeTo",
            MethodType.methodType(Class.forName("kotlin.ranges.ClosedFloatingPointRange",false,loader),float.class,float.class));
        Map<String,Object> nodes=new LinkedHashMap<>(),ranges=new LinkedHashMap<>();
        nodes.put("Mode",mode);nodes.put("Mode/Creative",creative);nodes.put("Mode/Vanilla",precision);
        var rootValues=new ArrayList<>(List.of(mode,enabled));
        for(var route:AbilityCatalog.instance().entriesForModule("Fly"))for(var rank:route.ranks())for(var setting:rank.settings().values()){
            String path=setting.path();if(nodes.containsKey(path))continue;
            String[] pieces=path.split("/");
            Object parent=null;String prefix="";
            for(int i=0;i<pieces.length-1;i++){
                prefix=prefix.isEmpty()?pieces[i]:prefix+"/"+pieces[i];
                Object group=nodes.get(prefix);
                if(group==null){
                    group=RuntimeValueProbe.makeConfig.newInstance(pieces[i],new ArrayList<>(),RuntimeValueProbe.constant(RuntimeValueProbe.rangeType,
                        pieces[i].equals("SprintSpeed")?"TOGGLEABLE":"CONFIGURABLE"),false);
                    ((List<Object>)RuntimeValueProbe.raw.invoke(parent)).add(group);nodes.put(prefix,group);
                }
                parent=group;
            }
            String name=pieces[pieces.length-1];Object leaf;
            if(setting.type().equals("boolean"))leaf=bool(name,!setting.booleanValue());
            else{
                float lo=path.endsWith("Glide")?-1F:path.endsWith("MaxVelocity")?1F:.1F;
                float hi=path.endsWith("Glide")?1F:path.endsWith("MaxVelocity")?20F:path.equals("Mode/Creative/Speed")?5F:10F;
                Object range=floatRange.invoke(lo,hi);ranges.put(path,range);leaf=RuntimeValueProbe.ranged(name,hi,"FLOAT",range);
            }
            nodes.put(path,leaf);
            if(parent==null)rootValues.add(leaf);else ((List<Object>)RuntimeValueProbe.raw.invoke(parent)).add(leaf);
        }
        RuntimeValueProbe.rules.clear();RuntimeValueProbe.bindings.clear();
        Object binding=RuntimeValueProbe.bind("Fly","fly",rootValues);
        RuntimeValueProbe.rule(binding,enabled,"Enabled",true,null);
        for(var entry:nodes.entrySet())if(entry.getValue()==mode||ranges.containsKey(entry.getKey())
                || RuntimeValueProbe.raw.invoke(entry.getValue()) instanceof Boolean){
            RuntimeValueProbe.rule(binding,entry.getValue(),entry.getKey(),false,ranges.get(entry.getKey()));
        }
        RuntimeValueProbe.field(RuntimeValueProbe.helper,"rules").set(null,RuntimeValueProbe.rules);
        RuntimeValueProbe.field(RuntimeValueProbe.helper,"bindings").set(null,RuntimeValueProbe.bindings);
        Method select=choiceType.getMethod("setByString",String.class),get=choiceType.getMethod("getActiveChoice");
        Object fakeModule=RuntimeValueProbe.bindings.keySet().iterator().next();
        GsonBuilder builder=new GsonBuilder();
        builder.addSerializationExclusionStrategy((ExclusionStrategy)Class.forName("net.ccbluex.liquidbounce.config.gson.stategies.ProtocolExclusionStrategy",true,loader).getConstructor().newInstance());
        builder.registerTypeHierarchyAdapter(RuntimeValueProbe.configurable,Class.forName("net.ccbluex.liquidbounce.config.gson.serializer.ConfigurableSerializer",true,loader).getConstructor(boolean.class,boolean.class,boolean.class).newInstance(true,true,false));
        builder.registerTypeAdapter(choiceType,Class.forName("net.ccbluex.liquidbounce.config.gson.serializer.ChoiceConfigurableSerializer",true,loader).getField("INSTANCE").get(null));
        // Same ClosedRange adapter registered by fixed LB's interopGson common adapters.
        // Plain Gson reflects Kotlin implementation fields instead of the actual {from,to} wire shape.
        builder.registerTypeHierarchyAdapter(Class.forName("kotlin.ranges.ClosedRange",false,loader),
            Class.forName("net.ccbluex.liquidbounce.config.gson.adapter.RangeAdapter",true,loader).getField("INSTANCE").get(null));
        Gson gson=builder.create();
        RuntimeValueProbe.publish.invoke(null,true,flyPolicy(0,0,200));
        GuiPolicyProbe.fly(loader,gson.toJsonTree(fakeModule,RuntimeValueProbe.configurable).getAsJsonObject(),0,0);
        RuntimeValueProbe.publish.invoke(null,true,flyPolicy(2,0,201));
        select.invoke(mode,"Vanilla");check(get.invoke(mode)==creative,"unlearned precise flight cannot select Vanilla");
        GuiPolicyProbe.fly(loader,gson.toJsonTree(fakeModule,RuntimeValueProbe.configurable).getAsJsonObject(),2,0);
        long revision=202;
        for(int base=1;base<=3;base++)for(int rank=1;rank<=2;rank++){
            RuntimeValueProbe.publish.invoke(null,true,flyPolicy(base,rank,revision++));
            check(get.invoke(mode)==creative,"learning precise flight does not silently change mode");
            select.invoke(mode,"Vanilla");check(get.invoke(mode)==precision,"learned Vanilla choice selectable");
            float horizontal=rank==1?.25F:.4F,vertical=rank==1?.2F:.3F;
            for(var cap:Map.of("Mode/Vanilla/BaseSpeed/Horizontal",horizontal,"Mode/Vanilla/BaseSpeed/Vertical",vertical,
                    "Mode/Creative/Speed",base==1?.1F:base==2?.15F:.2F,"Mode/Creative/MaxVelocity",1F).entrySet()){
                Object leaf=nodes.get(cap.getKey());RuntimeValueProbe.set.invoke(leaf,99F);
                RuntimeValueProbe.eq(cap.getValue(),RuntimeValueProbe.raw.invoke(leaf));
                RuntimeValueProbe.eq(cap.getValue(),RuntimeValueProbe.get.invoke(leaf));
                checks+=2;
            }
            for(String path:List.of("Mode/Vanilla/BaseSpeed/Horizontal","Mode/Vanilla/BaseSpeed/Vertical")){
                RuntimeValueProbe.set.invoke(nodes.get(path),-5F);RuntimeValueProbe.eq(.1F,RuntimeValueProbe.raw.invoke(nodes.get(path)));checks++;
            }
            for(float drift:new float[]{.75F,-.75F}){
                RuntimeValueProbe.set.invoke(nodes.get("Mode/Vanilla/Glide"),drift);
                RuntimeValueProbe.eq(0F,RuntimeValueProbe.raw.invoke(nodes.get("Mode/Vanilla/Glide")));checks++;
            }
            for(String path:List.of("Mode/Vanilla/SprintSpeed/Enabled","Mode/Vanilla/BypassVanillaCheck",
                    "Mode/Creative/SprintSpeed/Enabled","Mode/Creative/BypassVanillaCheck")){
                RuntimeValueProbe.set.invoke(nodes.get(path),true);
                check(Boolean.FALSE.equals(RuntimeValueProbe.raw.invoke(nodes.get(path))),"unsafe flight option remained enabled: "+path);
            }
            RuntimeValueProbe.value.getMethod("deserializeFrom",Gson.class,JsonElement.class).invoke(
                nodes.get("Mode/Vanilla/BaseSpeed/Horizontal"),new Gson(),new JsonPrimitive(9));
            RuntimeValueProbe.eq(horizontal,RuntimeValueProbe.raw.invoke(nodes.get("Mode/Vanilla/BaseSpeed/Horizontal")));checks++;
            GuiPolicyProbe.fly(loader,gson.toJsonTree(fakeModule,RuntimeValueProbe.configurable).getAsJsonObject(),base,rank);
            select.invoke(mode,"Jetpack");check(get.invoke(mode)==creative,"unapproved Fly mode falls back to Creative");
        }
        // Downgrade while Vanilla is selected guards both execution reads before reconciliation.
        RuntimeValueProbe.publish.invoke(null,true,flyPolicy(2,2,revision++));select.invoke(mode,"Vanilla");
        RuntimeValueProbe.set.invoke(nodes.get("Mode/Vanilla/BaseSpeed/Horizontal"),.4F);
        RuntimeValueProbe.set.invoke(nodes.get("Mode/Vanilla/BaseSpeed/Vertical"),.3F);
        RuntimeValueProbe.publish.invoke(null,true,flyPolicy(2,1,revision++));
        RuntimeValueProbe.eq(.25F,RuntimeValueProbe.get.invoke(nodes.get("Mode/Vanilla/BaseSpeed/Horizontal")));
        RuntimeValueProbe.eq(.2F,RuntimeValueProbe.get.invoke(nodes.get("Mode/Vanilla/BaseSpeed/Vertical")));checks+=2;
        RuntimeValueProbe.set.invoke(enabled,true);
        int precisionStops=fixture.getField("disables").getInt(precision),creativeStarts=fixture.getField("enables").getInt(creative);
        RuntimeValueProbe.publish.invoke(null,true,flyPolicy(2,0,revision++));
        check(get.invoke(mode)==creative,"refund immediately guards active Vanilla reads");
        Object access=RuntimeValueProbe.field(RuntimeValueProbe.helper,"access").get(null);
        Field enabledHandle=RuntimeValueProbe.field(access.getClass(),"moduleEnabled");Object old=enabledHandle.get(access);
        enabledHandle.set(access,MethodHandles.lookup().findStatic(RoutePolicyProbe.class,"moduleEnabled",MethodType.methodType(void.class,Object.class,boolean.class)));
        try{
            Method apply=RuntimeValueProbe.helper.getDeclaredMethod("apply",RuntimeValueProbe.binding);apply.setAccessible(true);apply.invoke(null,binding);
            check(stops==1&&!((Boolean)RuntimeValueProbe.raw.invoke(enabled)),"Fly refund stops enabled module exactly once");
            check(fixture.getField("disables").getInt(precision)==precisionStops+1,"raw Vanilla receives disable callback on refund");
            check(get.invoke(mode)==creative&&fixture.getField("enables").getInt(creative)==creativeStarts,"refund selects Creative without auto-enabling");
            check(!RuntimeValueProbe.field(RuntimeValueProbe.binding,"failed").getBoolean(binding),"Fly apply failed before completing real Value metadata");
        }finally{enabledHandle.set(access,old);}
        GuiPolicyProbe.fly(loader,gson.toJsonTree(fakeModule,RuntimeValueProbe.configurable).getAsJsonObject(),2,0);
        RuntimeValueProbe.publish.invoke(null,false,null);
        Method restore=RuntimeValueProbe.helper.getDeclaredMethod("restoreMetadata",RuntimeValueProbe.binding);restore.setAccessible(true);restore.invoke(null,binding);
        select.invoke(mode,"Jetpack");check(get.invoke(mode)==jetpack,"free mode restores original Fly choices");
        RuntimeValueProbe.set.invoke(nodes.get("Mode/Vanilla/BaseSpeed/Horizontal"),4F);
        RuntimeValueProbe.eq(4F,RuntimeValueProbe.raw.invoke(nodes.get("Mode/Vanilla/BaseSpeed/Horizontal")));checks++;
        System.out.println("PASS: "+(checks-initialChecks)+" Fly precise/base-independent cap, Glide/Sprint/Bypass, real Value import, downgrade, choice/refund assertions + "+(GuiPolicyProbe.checks-initialGuiChecks)+" actual serialized Fly GUI projection assertions; lifecycle uses explicit counters, not live Fly cleanup");
    }

    static Object bool(String name,boolean value)throws Exception{
        return RuntimeValueProbe.makeValue.newInstance(name,value,RuntimeValueProbe.constant(RuntimeValueProbe.rangeType,"BOOLEAN"),
            RuntimeValueProbe.constant(RuntimeValueProbe.makeValue.getParameterTypes()[3],"None"),false);
    }
}
