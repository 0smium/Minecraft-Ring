package dev.ermc.bridge.gameplay.skills;

import dev.ermc.bridge.api.CharacterBinding;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A recovery journal only: Skills remains the sole owner of spendable points and learned nodes. */
public final class GrowthStateStore {
    public record State(CharacterBinding binding, int catalog, String phase, String preset, int testPoints,
                        Set<String> backupNodes, Set<String> legacyNodes) {
        public State {
            Objects.requireNonNull(binding); backupNodes = Set.copyOf(backupNodes); legacyNodes = Set.copyOf(legacyNodes);
            if (catalog != 2 || !Set.of("ready", "migration", "preset_applying", "preset_active", "preset_clearing").contains(phase)
                    || !preset.matches("[a-z0-9_]{0,32}") || testPoints < 0 || testPoints > 1000
                    || backupNodes.size() > 256 || legacyNodes.size() > 6
                    || java.util.stream.Stream.concat(backupNodes.stream(), legacyNodes.stream()).anyMatch(s -> !s.matches("[a-z][a-z0-9_]{0,63}")))
                throw new IllegalArgumentException("Invalid growth recovery journal");
            if (phase.startsWith("preset_") && (preset.isEmpty() || testPoints == 0)) throw new IllegalArgumentException("Missing test preset");
            if ((phase.equals("ready") || phase.equals("migration")) && (!preset.isEmpty() || testPoints != 0))
                throw new IllegalArgumentException("Unexpected test source");
        }
        public State phase(String next) { return new State(binding, catalog, next, preset, testPoints, backupNodes, legacyNodes); }
        public State ready() { return new State(binding, catalog, "ready", "", 0, backupNodes, legacyNodes); }
        public boolean testActive() { return phase.equals("preset_active"); }
    }
    private final Path root;
    public GrowthStateStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path path(CharacterBinding binding) { return root.resolve(binding.playerId() + ".properties"); }
    public Optional<State> load(CharacterBinding binding) throws IOException {
        Path path = path(binding); if (Files.notExists(path)) return Optional.empty();
        if (Files.size(path) > 32768) throw new IOException("Oversized growth journal");
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
            if (!p.stringPropertyNames().equals(Set.of("version", "player", "campaign", "character", "catalog", "phase", "preset", "testPoints", "backupNodes", "legacyNodes"))
                    || !"1".equals(p.getProperty("version"))) throw new IllegalArgumentException("Unsupported journal schema");
            CharacterBinding observed = new CharacterBinding(UUID.fromString(p.getProperty("player")), UUID.fromString(p.getProperty("campaign")), UUID.fromString(p.getProperty("character")));
            if (!observed.equals(binding)) throw new IllegalArgumentException("Growth journal belongs to another character");
            return Optional.of(new State(binding, Integer.parseInt(p.getProperty("catalog")), p.getProperty("phase"), p.getProperty("preset"),
                    Integer.parseInt(p.getProperty("testPoints")), ids(p.getProperty("backupNodes")), ids(p.getProperty("legacyNodes"))));
        } catch (IllegalArgumentException e) { throw new IOException("Invalid growth journal: " + path, e); }
    }
    public void save(State state) throws IOException {
        Path target = path(state.binding()); Files.createDirectories(root); Properties p = new Properties();
        p.setProperty("version", "1"); p.setProperty("player", state.binding().playerId().toString()); p.setProperty("campaign", state.binding().campaignId().toString());
        p.setProperty("character", state.binding().characterId().toString()); p.setProperty("catalog", Integer.toString(state.catalog())); p.setProperty("phase", state.phase());
        p.setProperty("preset", state.preset()); p.setProperty("testPoints", Integer.toString(state.testPoints()));
        p.setProperty("backupNodes", String.join(",", new TreeSet<>(state.backupNodes()))); p.setProperty("legacyNodes", String.join(",", new TreeSet<>(state.legacyNodes())));
        StringWriter writer = new StringWriter(); p.store(writer, "Growth recovery journal; Skills owns point spending. Keep legacyNodes for rollback.");
        Path temporary = Files.createTempFile(root, ".growth-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(writer.toString()); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static Set<String> ids(String value) {
        if (value.isEmpty()) return Set.of(); List<String> list = Arrays.asList(value.split(",", -1));
        Set<String> result = Set.copyOf(list); if (result.size() != list.size()) throw new IllegalArgumentException("Duplicate recovery node"); return result;
    }
}
