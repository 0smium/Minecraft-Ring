package dev.ermc.bridge.client.integrations;

import com.google.gson.*;
import dev.ermc.bridge.api.CapabilitySnapshot.Grant;
import dev.ermc.bridge.api.CapabilitySnapshot.NumericRange;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog.Setting;
import java.util.*;

/** Read-only projection at LB's two REST responses. Never changes its registry or saved config. */
public final class LiquidBounceGuiPolicy {
    private LiquidBounceGuiPolicy() {}

    public static JsonElement modules(JsonElement source) {
        var view = CapabilityPolicyClient.view();
        if (!view.challengeActive()) return source;
        Set<String> allowed = LiquidBounceCapabilityAccess.guiModules(view);
        JsonArray result = new JsonArray();
        for (JsonElement item : source.getAsJsonArray())
            if (allowed.contains(item.getAsJsonObject().get("name").getAsString())) result.add(item.deepCopy());
        return result;
    }

    public static JsonElement settings(JsonElement source) {
        var view = CapabilityPolicyClient.view();
        if (!view.challengeActive()) return source;
        JsonObject root = source.getAsJsonObject();
        String module = root.get("name").getAsString();
        Set<String> allowed = LiquidBounceCapabilityAccess.guiModules(view);
        if (allowed.contains(module) && AbilityCatalog.instance().freeModules().contains(module)) return source;
        JsonObject output = root.deepCopy();
        output.add("value", new JsonArray());
        if (!allowed.contains(module) || view.snapshot() == null) return output;
        var entry = AbilityCatalog.instance().entryForModule(module).orElse(null);
        Grant grant = entry == null ? null : view.snapshot().abilities().get(entry.id());
        if (grant == null || !grant.unlocked() || grant.rank() > entry.ranks().size()) return output;
        var capability=LiquidBounceCapabilityAccess.guiCapability(module,view);
        if(capability==null)return output;
        Map<String, Setting> rules = new HashMap<>();
        capability.settings().values().forEach(s -> {
            Setting projected = LiquidBounceCapabilityAccess.guiSetting(module, s);
            if (projected != null) rules.put(projected.path(), projected);
        });
        JsonArray visible = output.getAsJsonArray("value");
        for (JsonElement child : root.getAsJsonArray("value")) {
            String name = child.getAsJsonObject().get("name").getAsString();
            JsonObject projected = Set.of("Enabled", "Bind").contains(name) ? child.getAsJsonObject().deepCopy()
                    : project(child.getAsJsonObject(), name, rules, capability.limits());
            if (projected != null) visible.add(projected);
        }
        return output;
    }

    private static JsonObject project(JsonObject source, String path, Map<String, Setting> rules, Map<String,NumericRange> limits) {
        Setting rule = rules.get(path);
        JsonObject output = source.deepCopy();
        String type = source.has("valueType") ? source.get("valueType").getAsString() : "";
        if (type.equals("CHOICE")) {
            if (rule == null || !rule.type().startsWith("choice")) return null;
            Set<String> permitted = rule.type().equals("choice_set") ? rule.allowedChoices() : Set.of(rule.stringValue());
            JsonObject choices = new JsonObject();
            boolean hasChildren = false;
            for (var choice : source.getAsJsonObject("choices").entrySet()) {
                if (!permitted.contains(choice.getKey())) continue;
                JsonObject branch = choice.getValue().getAsJsonObject().deepCopy();
                JsonArray children = children(branch, path + "/" + choice.getKey(), rules, limits);
                branch.add("value", children); choices.add(choice.getKey(), branch);
                hasChildren |= !children.isEmpty();
            }
            if (choices.isEmpty() || choices.size() == 1 && !hasChildren) return null;
            String active = source.get("active").getAsString();
            if (!choices.has(active)) active = choices.has(rule.stringValue()) ? rule.stringValue() : choices.keySet().iterator().next();
            output.addProperty("active", active);
            output.add("choices", choices);
            output.add("value", children(source, path, rules, limits));
            return output;
        }
        if (type.equals("CHOOSE")) {
            if (rule == null || !rule.type().equals("choice_set")) return null;
            JsonArray choices = new JsonArray();
            for (JsonElement choice : source.getAsJsonArray("choices"))
                if (rule.allowedChoices().contains(choice.getAsString())) choices.add(choice.deepCopy());
            if (choices.size() < 2) return null;
            output.add("choices", choices);
            if (!rule.allowedChoices().contains(output.get("value").getAsString())) output.addProperty("value", rule.stringValue());
            return output;
        }
        if (source.has("value") && source.get("value").isJsonArray()) {
            Setting enabled = rules.get(path + "/Enabled");
            if (type.equals("TOGGLEABLE") && enabled != null && Boolean.FALSE.equals(enabled.booleanValue())) return null;
            JsonArray children = children(source, path, rules, limits);
            if (children.isEmpty()) return null;
            output.add("value", children);
            // A fixed toggle is a group in this view. Same name/value nesting still round-trips
            // through ConfigSystem; the omitted Enabled value remains under the execution gate.
            if (type.equals("TOGGLEABLE")) output.addProperty("valueType", "CONFIGURABLE");
            return output;
        }
        if (rule == null || !(rule.type().equals("number") || rule.type().equals("range"))) return null;
        NumericRange permitted = limits.get(rule.limitKey());
        if (permitted == null || !source.has("range")) return null;
        JsonObject originalRange = source.getAsJsonObject("range");
        double lo = Math.max(permitted.minimum(), originalRange.get("from").getAsDouble());
        double hi = Math.min(permitted.maximum(), originalRange.get("to").getAsDouble());
        if (type.startsWith("INT")) { lo = Math.ceil(lo); hi = Math.floor(hi); }
        if (!Double.isFinite(lo) || !Double.isFinite(hi) || lo >= hi) return null;
        JsonObject range = new JsonObject(); range.addProperty("from", lo); range.addProperty("to", hi); output.add("range", range);
        if (rule.type().equals("number")) output.addProperty("value", clamp(output.get("value").getAsDouble(), lo, hi));
        else {
            JsonObject value = output.getAsJsonObject("value");
            value.addProperty("from", clamp(value.get("from").getAsDouble(), lo, hi));
            value.addProperty("to", clamp(value.get("to").getAsDouble(), lo, hi));
        }
        return output;
    }

    private static JsonArray children(JsonObject parent, String path, Map<String, Setting> rules, Map<String,NumericRange> limits) {
        JsonArray result = new JsonArray();
        for (JsonElement child : parent.getAsJsonArray("value")) {
            JsonObject value = child.getAsJsonObject();
            JsonObject projected = project(value, path + "/" + value.get("name").getAsString(), rules, limits);
            if (projected != null) result.add(projected);
        }
        return result;
    }
    private static double clamp(double value, double lo, double hi) { return Double.isFinite(value) ? Math.max(lo, Math.min(hi, value)) : lo; }
}
