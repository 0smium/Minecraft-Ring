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
    }
}
