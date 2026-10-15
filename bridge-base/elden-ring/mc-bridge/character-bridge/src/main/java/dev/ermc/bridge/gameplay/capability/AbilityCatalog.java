package dev.ermc.bridge.gameplay.capability;

import com.google.gson.*;
import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.CapabilitySnapshot.NumericRange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned shared content. No client classes, native addresses or independently spendable balance. */
public final class AbilityCatalog {
    public static final String RESOURCE = "/data/erbridge/ability_catalog.json";
    public record Setting(String type, String path, String limitKey, String stringValue, Boolean booleanValue, Set<String> allowedChoices) {
        public Setting {
            allowedChoices = Set.copyOf(allowedChoices);
            if (!Set.of("number", "range", "choice", "choice_set", "boolean").contains(type) || path == null || path.isBlank())
                throw new IllegalArgumentException("Invalid setting mapping");
            if ((type.equals("number") || type.equals("range")) && (limitKey == null || limitKey.isBlank())
                    || (type.equals("choice") || type.equals("choice_set")) && (stringValue == null || stringValue.isBlank())
                    || type.equals("boolean") && booleanValue == null) throw new IllegalArgumentException("Missing setting value");
            if (type.equals("choice_set") ? allowedChoices.size() < 2 || !allowedChoices.contains(stringValue)
                    : !allowedChoices.isEmpty()) throw new IllegalArgumentException("Invalid allowed choices / safe fallback");
        }
    }
    public record Rank(String node, int cost, int requiredSpent, Set<String> prerequisites, Set<String> modes,
                       Map<String, NumericRange> limits, Map<String, Setting> settings) {
        public Rank {
            prerequisites = Set.copyOf(prerequisites); modes = Set.copyOf(modes);
            limits = Map.copyOf(limits); settings = Map.copyOf(settings);
            if (!node.matches("[a-z][a-z0-9_]{0,63}") || cost < 1 || cost > 100 || requiredSpent < 0 || requiredSpent > 1000 || modes.isEmpty())
                throw new IllegalArgumentException("Invalid rank");
            for (Setting setting : settings.values()) if (setting.limitKey() != null && !limits.containsKey(setting.limitKey()))
                throw new IllegalArgumentException("Mapping refers to missing numeric limit");
        }
    }
    public record Entry(Ability id, String category, String name, String description, String module,
                        String icon, List<Rank> ranks, Ability branchOf) {
        public Entry(Ability id, String category, String name, String description, String module, String icon, List<Rank> ranks) {
            this(id, category, name, description, module, icon, ranks, null);
        }
        public Entry {
            ranks = List.copyOf(ranks);
            if (category.isBlank() || name.isBlank() || description.isBlank() || module.isBlank() || ranks.isEmpty() || ranks.size() > 8)
                throw new IllegalArgumentException("Invalid ability entry");
        }
    }
    public record Preset(String id, String name, int budget, Map<Ability, Integer> ranks) {
        public Preset { ranks = Map.copyOf(ranks); }
    }
    private static final class Holder { static final AbilityCatalog INSTANCE = load(); }
    private final int version;
    private final List<Entry> entries;
    private final Map<Ability, Entry> byId;
    private final Map<String, Rank> nodes;
    private final Map<String, String> categories;
    private final Set<String> freeModules;
    private final Map<String, Preset> presets;

    public static AbilityCatalog instance() { return Holder.INSTANCE; }
    public int version() { return version; }
    public List<Entry> entries() { return entries; }
    public Map<String, String> categories() { return categories; }
    public Set<String> freeModules() { return freeModules; }
    public Map<String, Rank> nodes() { return nodes; }
    public Map<String, Preset> presets() { return presets; }
    public Entry entry(Ability id) {
        Entry found = byId.get(id); if (found == null) throw new IllegalArgumentException("Unknown ability: " + id); return found;
    }
    public Optional<Entry> entryForModule(String module) { return entries.stream().filter(e -> e.branchOf() == null && e.module().equals(module)).findFirst(); }
    public List<Entry> entriesForModule(String module) { return entries.stream().filter(e -> e.module().equals(module)).toList(); }
    public AbilityPolicy policy(long revision, boolean showLocked) {
        Map<Ability, List<AbilityPolicy.Tier>> result = new LinkedHashMap<>();
        entries.forEach(e -> result.put(e.id(), e.ranks().stream().map(r -> new AbilityPolicy.Tier(r.modes(), r.limits())).toList()));
        return new AbilityPolicy(revision, showLocked, result);
    }
    public int cost(Map<Ability, Integer> ranks) {
        return selectedNodes(ranks).stream().mapToInt(n -> nodes.get(n).cost()).sum();
    }
    public Set<String> selectedNodes(Map<Ability, Integer> ranks) {
        Set<String> selected = new LinkedHashSet<>();
        for (var rank : ranks.entrySet()) {
            Entry entry = entry(rank.getKey());
            if (rank.getValue() < 0 || rank.getValue() > entry.ranks().size()) throw new IllegalArgumentException("Invalid selected rank");
            entry.ranks().subList(0, rank.getValue()).forEach(r -> selected.add(r.node()));
        }
        for (String node : selected) if (!selected.containsAll(nodes.get(node).prerequisites()))
            throw new IllegalArgumentException("Missing prerequisite for " + node);
        purchaseOrder(selected);
        return Set.copyOf(selected);
    }
    public List<String> purchaseOrder(Set<String> selected) {
        if (!nodes.keySet().containsAll(selected)) throw new IllegalArgumentException("Unknown selected node");
        List<String> order = new ArrayList<>(); Set<String> learned = new HashSet<>(); int spent = 0;
        while (learned.size() < selected.size()) {
            boolean changed = false;
            for (String id : nodes.keySet()) {
                Rank node = nodes.get(id);
                if (!selected.contains(id) || learned.contains(id) || !learned.containsAll(node.prerequisites()) || spent < node.requiredSpent()) continue;
                learned.add(id); order.add(id); spent = Math.addExact(spent, node.cost()); changed = true;
            }
            if (!changed) throw new IllegalArgumentException("Selected build cannot reach its stage/prerequisite requirements");
        }
        return List.copyOf(order);
    }
    public Map<Ability, Integer> ranks(Set<String> unlocked) {
        if (!nodes.keySet().containsAll(unlocked)) throw new IllegalArgumentException("Unknown unlocked node; explicit migration required");
        purchaseOrder(unlocked);
        Map<Ability, Integer> result = new LinkedHashMap<>();
        for (Entry entry : entries) {
            int learned = 0; boolean gap = false;
            for (Rank rank : entry.ranks()) {
                if (unlocked.contains(rank.node())) {
                    if (gap || !unlocked.containsAll(rank.prerequisites())) throw new IllegalArgumentException("Broken prerequisite chain");
                    learned++;
                } else gap = true;
            }
            result.put(entry.id(), learned);
        }
        return Map.copyOf(result);
    }
    private static AbilityCatalog load() {
        try (InputStream input = AbilityCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing ability catalog");
            return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Ability catalog failed closed", e); }
    }
    /** Public for offline validation of the same shipped parser. */
    public static AbilityCatalog parse(Reader source) { return new AbilityCatalog(JsonParser.parseReader(source).getAsJsonObject()); }
    private AbilityCatalog(JsonObject root) {
        fields(root, Set.of("version", "categories", "freeModules", "abilities", "presets"));
        version = root.get("version").getAsInt();
        if (version != 2) throw new IllegalArgumentException("Unsupported ability catalog");
        Map<String, String> group = new LinkedHashMap<>();
        root.getAsJsonObject("categories").entrySet().forEach(e -> group.put(e.getKey(), e.getValue().getAsString()));
        categories = Collections.unmodifiableMap(group);
        freeModules = strings(root.getAsJsonArray("freeModules"));
        List<Entry> list = new ArrayList<>(); Map<Ability, Entry> map = new LinkedHashMap<>();
        Map<String, Rank> nodeMap = new LinkedHashMap<>(); Set<String> modules = new HashSet<>();
        for (JsonElement value : root.getAsJsonArray("abilities")) {
            JsonObject a = value.getAsJsonObject();
            fields(a, a.has("branchOf") ? Set.of("id", "category", "name", "description", "module", "icon", "ranks", "branchOf")
                    : Set.of("id", "category", "name", "description", "module", "icon", "ranks"));
            Ability id = new Ability(a.get("id").getAsString()); List<Rank> ranks = new ArrayList<>();
            for (JsonElement tier : a.getAsJsonArray("ranks")) {
                JsonObject r = tier.getAsJsonObject();
                fields(r, Set.of("node", "cost", "requiredSpent", "prerequisites", "modes", "limits", "settings"));
                Map<String, NumericRange> limits = new LinkedHashMap<>();
                r.getAsJsonObject("limits").entrySet().forEach(e -> {
                    JsonArray range = e.getValue().getAsJsonArray();
                    if (range.size() != 2) throw new IllegalArgumentException("Invalid numeric range");
                    limits.put(e.getKey(), new NumericRange(range.get(0).getAsDouble(), range.get(1).getAsDouble()));
                });
                Map<String, Setting> settings = new LinkedHashMap<>();
                r.getAsJsonObject("settings").entrySet().forEach(e -> {
                    JsonObject s = e.getValue().getAsJsonObject();
                    Set<String> expected = switch (s.get("type").getAsString()) {
                        case "number", "range" -> Set.of("type", "path", "limitKey");
                        case "choice" -> Set.of("type", "path", "stringValue");
                        case "choice_set" -> Set.of("type", "path", "stringValue", "allowedChoices");
                        case "boolean" -> Set.of("type", "path", "booleanValue");
                        default -> throw new IllegalArgumentException("Unknown setting type");
                    };
                    fields(s, expected);
                    settings.put(e.getKey(), new Setting(s.get("type").getAsString(), s.get("path").getAsString(),
                            string(s, "limitKey"), string(s, "stringValue"), s.has("booleanValue") ? s.get("booleanValue").getAsBoolean() : null,
                            s.has("allowedChoices") ? strings(s.getAsJsonArray("allowedChoices")) : Set.of()));
                });
                Rank rank = new Rank(r.get("node").getAsString(), r.get("cost").getAsInt(), r.get("requiredSpent").getAsInt(), strings(r.getAsJsonArray("prerequisites")),
                        strings(r.getAsJsonArray("modes")), limits, settings);
                if (nodeMap.putIfAbsent(rank.node(), rank) != null) throw new IllegalArgumentException("Duplicate skill node");
                ranks.add(rank);
            }
            Entry entry = new Entry(id, a.get("category").getAsString(), a.get("name").getAsString(), a.get("description").getAsString(),
                    a.get("module").getAsString(), a.get("icon").getAsString(), ranks,
                    a.has("branchOf") ? new Ability(a.get("branchOf").getAsString()) : null);
            if (!categories.containsKey(entry.category()) || map.putIfAbsent(id, entry) != null || entry.branchOf() == null && !modules.add(entry.module())
                    || freeModules.contains(entry.module())) throw new IllegalArgumentException("Duplicate/unknown/free paid ability");
            for (int i = 1; i < ranks.size(); i++) if (!ranks.get(i).prerequisites().contains(ranks.get(i-1).node()))
                throw new IllegalArgumentException("Ranks must require their previous rank");
            list.add(entry);
        }
        if (list.isEmpty() || list.size() > 64 || nodeMap.size() > 256) throw new IllegalArgumentException("Oversized/empty catalog");
        entries = List.copyOf(list); byId = Map.copyOf(map); nodes = Collections.unmodifiableMap(nodeMap);
        for (Entry branch : entries) if (branch.branchOf() != null) {
            Entry parent = byId.get(branch.branchOf());
            if (parent == null || parent.branchOf() != null || !parent.module().equals(branch.module())
                    || !branch.ranks().get(0).prerequisites().contains(parent.ranks().get(0).node()))
                throw new IllegalArgumentException("Branch must depend on its module's base entry");
            // A branch only adds mode-specific settings. Shared fixed guards cannot be weakened.
            for (Rank rank : branch.ranks()) {
                Setting mode=rank.settings().values().stream().filter(s->s.path().equals("Mode")).findFirst().orElse(null);
                if(mode==null || !mode.type().startsWith("choice")) throw new IllegalArgumentException("Branch must select a real module mode");
                for (Setting setting : rank.settings().values()) {
                if (!setting.path().equals("Mode") && !setting.path().startsWith("Mode/"))
                    throw new IllegalArgumentException("Branch cannot override shared module guards");
                for (Rank base : parent.ranks()) {
                    if (!Collections.disjoint(rank.limits().keySet(), base.limits().keySet())) throw new IllegalArgumentException("Branch numeric keys must be distinct");
                    if(!setting.path().equals("Mode") && base.settings().values().stream().anyMatch(s->s.path().equals(setting.path())))
                        throw new IllegalArgumentException("Branch cannot replace base-mode settings");
                }
                }
            }
        }
        for (Rank rank : nodes.values()) if (!nodes.keySet().containsAll(rank.prerequisites())) throw new IllegalArgumentException("Unknown prerequisite");
        // A full rank selection also validates every prerequisite and graph reachability.
        Set<String> reached = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) for (Rank rank : nodes.values()) if (reached.containsAll(rank.prerequisites())) reached.add(rank.node());
        if (reached.size() != nodes.size()) throw new IllegalArgumentException("Cyclic prerequisites");
        Map<String, Preset> presetMap = new LinkedHashMap<>();
        for (JsonElement value : root.getAsJsonArray("presets")) {
            JsonObject p = value.getAsJsonObject(); fields(p, Set.of("id", "name", "budget", "ranks"));
            Map<Ability, Integer> ranks = new LinkedHashMap<>();
            p.getAsJsonObject("ranks").entrySet().forEach(e -> ranks.put(new Ability(e.getKey()), e.getValue().getAsInt()));
            Preset preset = new Preset(p.get("id").getAsString(), p.get("name").getAsString(), p.get("budget").getAsInt(), ranks);
            if (!preset.id().matches("[a-z][a-z0-9_]{0,31}") || preset.budget() < cost(ranks) || preset.budget() > 1000
                    || presetMap.putIfAbsent(preset.id(), preset) != null) throw new IllegalArgumentException("Invalid preset");
        }
        presets = Collections.unmodifiableMap(presetMap);
    }
    private static String string(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsString() : null; }
    private static Set<String> strings(JsonArray array) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement element : array) if (!result.add(element.getAsString()) || element.getAsString().isBlank()) throw new IllegalArgumentException("Duplicate/empty ID");
        return Set.copyOf(result);
    }
    private static void fields(JsonObject object, Set<String> expected) {
        if (!object.keySet().equals(expected)) throw new IllegalArgumentException("Unknown/missing catalog fields: " + object.keySet());
    }
}
