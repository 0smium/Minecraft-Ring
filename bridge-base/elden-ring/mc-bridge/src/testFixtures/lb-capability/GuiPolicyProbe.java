import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;

/** Actual fixed-LB serializers and Value objects plus explicit module/tree fixtures, no browser. */
public final class GuiPolicyProbe {
  static int checks;
  static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
  static JsonObject item(JsonObject root,String name){for(var e:root.getAsJsonArray("value"))if(e.getAsJsonObject().get("name").getAsString().equals(name))return e.getAsJsonObject();return null;}
  static Object singleton(ClassLoader loader,String name)throws Exception{return Class.forName(name,true,loader).getField("INSTANCE").get(null);}
  /** Input is serialized from real LB ChoiceConfigurable/RangedValue objects in RoutePolicyProbe. */
  public static void fly(ClassLoader loader,JsonObject original,int baseRank,int precisionRank)throws Throwable{
    Class<?> gui=Class.forName("dev.ermc.bridge.client.integrations.LiquidBounceGuiPolicy",true,loader);
    Method settings=gui.getMethod("settings",JsonElement.class),modules=gui.getMethod("modules",JsonElement.class);
    String before=original.toString();
    JsonObject shown=(JsonObject)settings.invoke(null,original);
    JsonArray listed=(JsonArray)modules.invoke(null,JsonParser.parseString("[{\"name\":\"Fly\"}]"));
    check(listed.size()==(baseRank==0?0:1),"Fly module visibility follows base unlock");
    if(baseRank==0){
      check(shown.getAsJsonArray("value").isEmpty(),"unlearned Fly settings remain hidden");
      return;
    }
    var mode=item(shown,"Mode");
    check(mode!=null,"learned adjustable Fly mode tree missing");
    check(mode.getAsJsonObject("choices").keySet().equals(precisionRank==0?Set.of("Creative"):Set.of("Creative","Vanilla")),
        "Fly GUI exposes exactly learned modes");
    var creative=mode.getAsJsonObject("choices").getAsJsonObject("Creative");
    check(item(creative,"MaxVelocity")==null&&item(creative,"SprintSpeed")==null&&item(creative,"BypassVanillaCheck")==null
        &&item(creative,"ForceFlight")==null,"fixed Creative implementation settings hidden");
    if(baseRank==1)check(item(creative,"Speed")==null,"fixed Creative I speed should not be an adjustable slider");
    else{
      var speed=item(creative,"Speed");check(speed!=null,"Creative speed slider removed by precise branch");
      check(Math.abs(speed.getAsJsonObject("range").get("to").getAsDouble()-(baseRank==2?.15:.2))<.00001,
          "precise branch overwrote Creative speed cap in GUI");
    }
    if(precisionRank>0){
      var vanilla=mode.getAsJsonObject("choices").getAsJsonObject("Vanilla");
      var base=item(vanilla,"BaseSpeed");check(base!=null,"Vanilla BaseSpeed group must preserve actual LB path");
      var horizontal=item(base,"Horizontal");var vertical=item(base,"Vertical");
      check(horizontal!=null&&vertical!=null,"independent precise flight sliders missing");
      check(Math.abs(horizontal.getAsJsonObject("range").get("from").getAsDouble()-.1)<.00001
          &&Math.abs(vertical.getAsJsonObject("range").get("from").getAsDouble()-.1)<.00001,"precise flight minimum GUI caps");
      check(Math.abs(horizontal.getAsJsonObject("range").get("to").getAsDouble()-(precisionRank==1?.25:.4))<.00001
          &&Math.abs(vertical.getAsJsonObject("range").get("to").getAsDouble()-(precisionRank==1?.2:.3))<.00001,
          "precise flight rank GUI maximum caps");
      check(item(vanilla,"Glide")==null&&item(vanilla,"SprintSpeed")==null&&item(vanilla,"BypassVanillaCheck")==null,
          "fixed hover/sprint/bypass settings must not appear adjustable");
      check(item(original,"Mode").getAsJsonObject("choices").has("Jetpack"),"test must retain real unapproved source option");
    }
    check(original.toString().equals(before),"Fly GUI projection mutated original LB serialized config");
  }
  public static void run(ClassLoader loader)throws Throwable{
    Class<?> gui=Class.forName("dev.ermc.bridge.client.integrations.LiquidBounceGuiPolicy",true,loader);
    Method modules=gui.getMethod("modules",JsonElement.class),settings=gui.getMethod("settings",JsonElement.class);
    Object hud=RuntimeValueProbe.makeConfig.newInstance("HUD",new ArrayList<>(),RuntimeValueProbe.constant(RuntimeValueProbe.rangeType,"CONFIGURABLE"),false);
    RuntimeValueProbe.bindings.put(hud,RuntimeValueProbe.makeBinding.newInstance(hud,"HUD",null,true));
    RuntimeValueProbe.field(RuntimeValueProbe.helper,"bindings").set(null,RuntimeValueProbe.bindings);
    JsonArray all=JsonParser.parseString("[{\"name\":\"HUD\"},{\"name\":\"Speed\"},{\"name\":\"AirJump\"},{\"name\":\"Uncatalogued\"}]").getAsJsonArray();
    check(modules.invoke(null,all)==all,"free response unchanged");
    RuntimeValueProbe.publish.invoke(null,true,RuntimeValueProbe.snapshot(0,10));
    check(((JsonArray)modules.invoke(null,all)).size()==1,"zero-point challenge only free module");
    RuntimeValueProbe.publish.invoke(null,true,RuntimeValueProbe.snapshot(1,11));
    check(((JsonArray)modules.invoke(null,all)).size()==3,"learned modules appear, unknown stays absent");
    check(all.size()==4,"list projection never mutates upstream JSON");
    JsonObject hudJson=JsonParser.parseString("{\"name\":\"HUD\",\"value\":[{\"name\":\"Blur\",\"value\":false,\"valueType\":\"BOOLEAN\"}]}").getAsJsonObject();
    check(settings.invoke(null,hudJson)==hudJson,"free HUD customization retained");

    // Serialize the actual LB AirJump enum / ChooseListValue from the preceding real-Value fixture.
    GsonBuilder builder=new GsonBuilder();
    builder.addSerializationExclusionStrategy((ExclusionStrategy)Class.forName("net.ccbluex.liquidbounce.config.gson.stategies.ProtocolExclusionStrategy",true,loader).getConstructor().newInstance());
    var serializerType=Class.forName("net.ccbluex.liquidbounce.config.gson.serializer.ConfigurableSerializer",true,loader);
    builder.registerTypeHierarchyAdapter(RuntimeValueProbe.configurable,serializerType.getConstructor(boolean.class,boolean.class,boolean.class).newInstance(true,true,false));
    builder.registerTypeHierarchyAdapter(Class.forName("net.ccbluex.liquidbounce.config.types.NamedChoice",false,loader),singleton(loader,"net.ccbluex.liquidbounce.config.gson.serializer.EnumChoiceSerializer"));
    Gson interop=builder.create();
    Object jump=RuntimeValueProbe.bindings.keySet().stream().filter(v->{try{return RuntimeValueProbe.value.getMethod("getName").invoke(v).equals("AirJump");}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow();
    JsonObject original=interop.toJsonTree(jump,RuntimeValueProbe.configurable).getAsJsonObject();String before=original.toString();
    JsonObject rankOne=(JsonObject)settings.invoke(null,original);
    check(item(rankOne,"Mode")==null,"fixed single enum selector omitted");
    RuntimeValueProbe.publish.invoke(null,true,RuntimeValueProbe.snapshot(2,12));
    JsonObject rankTwo=(JsonObject)settings.invoke(null,original);
    check(item(rankTwo,"Mode")!=null,"mastery mode missing: source="+original+" result="+rankTwo);
    check(item(rankTwo,"Mode").getAsJsonArray("choices").size()==2,"mastery exposes actual double/free jump choices");
    check(!item(rankTwo,"Mode").getAsJsonArray("choices").toString().contains("Ghost"),"unapproved real enum choice omitted");
    check(original.toString().equals(before),"real LB interop serialization not mutated");
    builder=new GsonBuilder();
    builder.addSerializationExclusionStrategy((ExclusionStrategy)Class.forName("net.ccbluex.liquidbounce.config.gson.stategies.ExcludeStrategy",true,loader).getConstructor().newInstance());
    builder.registerTypeHierarchyAdapter(RuntimeValueProbe.configurable,serializerType.getConstructor(boolean.class,boolean.class,boolean.class).newInstance(false,true,true));
    builder.registerTypeHierarchyAdapter(Class.forName("net.ccbluex.liquidbounce.config.types.NamedChoice",false,loader),singleton(loader,"net.ccbluex.liquidbounce.config.gson.serializer.EnumChoiceSerializer"));
    Gson file=builder.create();String fileBefore=file.toJson(jump,RuntimeValueProbe.configurable);
    settings.invoke(null,original);
    check(file.toJson(jump,RuntimeValueProbe.configurable).equals(fileBefore),"GUI filter does not modify actual FILE serialization");

    // Exact fixed-LB CHOICE/TOGGLEABLE nesting; only one adjustable leaf is authorised here.
    JsonObject speed=JsonParser.parseString("""
    {"name":"Speed","valueType":"CONFIGURABLE","value":[
      {"name":"Enabled","value":false,"valueType":"BOOLEAN"},
      {"name":"Bind","value":{},"valueType":"BIND"},
      {"name":"Hidden","value":false,"valueType":"BOOLEAN"},
      {"name":"Locked","value":false,"valueType":"BOOLEAN"},
      {"name":"Mode","active":"Custom","value":[],"valueType":"CHOICE","choices":{
        "Custom":{"name":"Custom","valueType":"CONFIGURABLE","value":[
          {"name":"TimerSpeed","valueType":"FLOAT","value":1,"range":{"from":0.1,"to":10}},
          {"name":"Strafe","valueType":"TOGGLEABLE","value":[
            {"name":"Enabled","value":true,"valueType":"BOOLEAN"},
            {"name":"CustomSpeed","value":true,"valueType":"BOOLEAN"},
            {"name":"Speed","valueType":"FLOAT","value":0.3,"range":{"from":0.1,"to":10}},
            {"name":"Strength","valueType":"FLOAT","value":1,"range":{"from":0,"to":1}}]}]},
        "BHop":{"name":"BHop","valueType":"CONFIGURABLE","value":[]}}}]}
    """).getAsJsonObject();
    RuntimeValueProbe.publish.invoke(null,true,RuntimeValueProbe.snapshot(1,13));
    JsonObject shown=(JsonObject)settings.invoke(null,speed);
    check(item(shown,"Hidden")==null && item(shown,"Locked")==null,"hidden/locked implementation options omitted");
    check(item(shown,"Enabled")!=null && item(shown,"Bind")!=null,"activation and bind retained");
    var mode=item(shown,"Mode");check(mode.getAsJsonObject("choices").size()==1,"only authorised choice remains");
    var custom=mode.getAsJsonObject("choices").getAsJsonObject("Custom");
    check(item(custom,"TimerSpeed")==null,"fixed numeric option omitted");
    var strafe=item(custom,"Strafe");check(strafe.get("valueType").getAsString().equals("CONFIGURABLE"),"fixed toggle displayed as group, same config path");
    check(item(strafe,"Enabled")==null && item(strafe,"CustomSpeed")==null && item(strafe,"Strength")==null,"fixed group children omitted");
    check(item(strafe,"Speed").getAsJsonObject("range").get("to").getAsDouble()==.3,"rank slider bound");
    item(strafe,"Speed").addProperty("value",.25);
    check(item(item(speed,"Mode").getAsJsonObject("choices").getAsJsonObject("Custom"),"Strafe")!=null,"source path still intact");
    check(item(item(speed,"Mode").getAsJsonObject("choices").getAsJsonObject("Custom"),"TimerSpeed")!=null,"projection did not prune saved tree");
    // The preserved name/value/choices path resolves to the same original Value, as ConfigSystem's ordinary walk does.
    Object speedValue=RuntimeValueProbe.rules.keySet().stream().filter(v->{try{return RuntimeValueProbe.value.getMethod("getName").invoke(v).equals("Speed");}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow();
    RuntimeValueProbe.value.getMethod("deserializeFrom",Gson.class,JsonElement.class).invoke(speedValue,new Gson(),item(strafe,"Speed").get("value"));
    RuntimeValueProbe.eq(.25F,RuntimeValueProbe.raw.invoke(speedValue));
    check(item(strafe,"Speed").get("name").getAsString().equals("Speed"),"real Value import consumes projected leaf under original path");
    RuntimeValueProbe.publish.invoke(null,true,RuntimeValueProbe.snapshot(0,14));
    check(((JsonObject)settings.invoke(null,speed)).getAsJsonArray("value").isEmpty(),"refund hides settings too");
    RuntimeValueProbe.publish.invoke(null,true,null);
    check(((JsonArray)modules.invoke(null,all)).size()==1,"explicit null authority leaves only free UI");
    RuntimeValueProbe.publish.invoke(null,false,null);
    check(settings.invoke(null,speed)==speed && modules.invoke(null,all)==all,"free mode original UI returned");
    System.out.println("PASS: "+checks+" GUI policy assertions; actual LB serializers/enum/Value import plus explicit CHOICE JSON fixture; no module singletons/browser");
  }
}
