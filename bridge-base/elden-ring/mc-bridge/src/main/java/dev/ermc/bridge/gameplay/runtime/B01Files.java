package dev.ermc.bridge.gameplay.runtime;

import dev.ermc.bridge.api.CharacterBinding;
import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.CapabilitySnapshot.NumericRange;
import dev.ermc.bridge.gameplay.capability.AbilityPolicy;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** World-local configuration and explicit binding. Skill spending remains entirely in Pufferfish. */
public final class B01Files {
    // All file IO requires one integrated-server-thread owner; the MC world session lock excludes other server processes.
    public record Settings(UUID campaign, boolean challenge, long revision, int pointsPerLevel,
                           int startingPoints, boolean showLocked, List<Double> speed, List<Double> aura) {
        public Settings {
            Objects.requireNonNull(campaign);
            speed = List.copyOf(speed); aura = List.copyOf(aura);
            if (revision < 1 || pointsPerLevel < 1 || pointsPerLevel > 100 || startingPoints < 0 || startingPoints > 1000)
                throw new IllegalArgumentException("Invalid B01 point curve/revision");
            validateTiers(speed, .05, .6); validateTiers(aura, 1, 6);
        }
        public AbilityPolicy policy() {
            return new AbilityPolicy(revision, showLocked, Map.of(
                    Ability.SPEED, tiers(speed, "ground_custom", "speed", .05),
                    Ability.AURA, tiers(aura, "single_target", "range", 1)));
        }
        public Settings mode(boolean value) { return new Settings(campaign, value, Math.incrementExact(revision), pointsPerLevel, startingPoints, showLocked, speed, aura); }
        public Settings revision(long value) { return new Settings(campaign, challenge, value, pointsPerLevel, startingPoints, showLocked, speed, aura); }
    }
    public record Binding(CharacterBinding identity, long weakFingerprint, String observedName) {
        public Binding {
            Objects.requireNonNull(identity); Objects.requireNonNull(observedName);
            if (weakFingerprint == 0 || observedName.isBlank() || observedName.length() > 16) throw new IllegalArgumentException("Invalid observed character name");
        }
        public boolean matches(long fingerprint, String name) { return weakFingerprint == fingerprint && observedName.equals(name); }
    }
    private final Path root;
    public B01Files(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path root() { return root; }
    public Path configPath() { return root.resolve("b01.properties"); }
    public Settings loadSettings(boolean create) throws IOException {
        if (create && Files.notExists(configPath())) {
            Settings initial = new Settings(UUID.randomUUID(), false, 1, 1, 0, true, List.of(.30,.33,.36), List.of(2.5,3.0,3.5));
            saveSettings(initial); return initial;
        }
        Properties p = read(configPath());
        try {
            fields(p, Set.of("version","campaign","challenge","revision","pointsPerLevel","startingPoints","showLocked","speed","aura"));
            return new Settings(UUID.fromString(p.getProperty("campaign")), bool(p,"challenge"), Long.parseLong(p.getProperty("revision")),
                    Integer.parseInt(p.getProperty("pointsPerLevel")), Integer.parseInt(p.getProperty("startingPoints")), bool(p,"showLocked"), numbers(p,"speed"), numbers(p,"aura"));
        } catch (RuntimeException invalid) { throw new IOException("Invalid B01 configuration: " + configPath(), invalid); }
    }
    public void saveSettings(Settings s) throws IOException {
        Properties p = new Properties(); p.setProperty("version","1");
        p.setProperty("campaign",s.campaign.toString()); p.setProperty("challenge",Boolean.toString(s.challenge));
        p.setProperty("revision",Long.toString(s.revision)); p.setProperty("pointsPerLevel",Integer.toString(s.pointsPerLevel));
        p.setProperty("startingPoints",Integer.toString(s.startingPoints)); p.setProperty("showLocked",Boolean.toString(s.showLocked));
        p.setProperty("speed",join(s.speed)); p.setProperty("aura",join(s.aura)); write(configPath(),p);
    }
    public Optional<Binding> loadBinding(UUID owner, UUID campaign) throws IOException {
        Path file = root.resolve("bindings").resolve(owner + ".properties");
        if (Files.notExists(file)) return Optional.empty();
        Properties p = read(file);
        try {
            fields(p, Set.of("version","player","campaign","character","weakFingerprint","observedName"));
            CharacterBinding b = new CharacterBinding(UUID.fromString(p.getProperty("player")), UUID.fromString(p.getProperty("campaign")), UUID.fromString(p.getProperty("character")));
            if (!b.playerId().equals(owner) || !b.campaignId().equals(campaign)) throw new IllegalArgumentException("Binding/world mismatch");
            return Optional.of(new Binding(b, Long.parseUnsignedLong(p.getProperty("weakFingerprint"),16), p.getProperty("observedName")));
        } catch (RuntimeException invalid) { throw new IOException("Invalid B01 binding: " + file, invalid); }
    }
    public void createBinding(Binding b) throws IOException {
        Path file = root.resolve("bindings").resolve(b.identity.playerId()+".properties");
        if (!Files.notExists(file)) throw new IOException("A binding already exists; use a separate world for another ER character");
        Properties p = new Properties(); p.setProperty("version","1");
        p.setProperty("player",b.identity.playerId().toString()); p.setProperty("campaign",b.identity.campaignId().toString());
        p.setProperty("character",b.identity.characterId().toString()); p.setProperty("weakFingerprint",Long.toUnsignedString(b.weakFingerprint,16));
        p.setProperty("observedName",b.observedName); write(file,p);
    }
    private static void validateTiers(List<Double> values, double minimum, double maximum) {
        if (values.size()!=3) throw new IllegalArgumentException("B01 requires three tiers");
        double previous=minimum;
        for (double v:values) { if (!Double.isFinite(v)||v<previous||v>maximum) throw new IllegalArgumentException("Invalid tier cap"); previous=v; }
    }
    private static List<AbilityPolicy.Tier> tiers(List<Double> values,String mode,String key,double min) {
        return values.stream().map(v->new AbilityPolicy.Tier(Set.of(mode),Map.of(key,new NumericRange(min,v)))).toList();
    }
    private static List<Double> numbers(Properties p,String key) { return Arrays.stream(p.getProperty(key).split(",",-1)).map(Double::valueOf).toList(); }
    private static String join(List<Double> values) { return String.join(",",values.stream().map(Object::toString).toList()); }
    private static boolean bool(Properties p,String key) {
        String v=p.getProperty(key); if (!"true".equals(v)&&!"false".equals(v)) throw new IllegalArgumentException("Invalid boolean"); return Boolean.parseBoolean(v);
    }
    private static void fields(Properties p,Set<String> expected) {
        if (!p.stringPropertyNames().equals(expected)||!"1".equals(p.getProperty("version"))) throw new IllegalArgumentException("Unsupported format");
    }
    private static Properties read(Path file) throws IOException {
        if (Files.size(file)>8192) throw new IOException("Oversized B01 file");
        Properties p=new Properties();
        try (var r=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { p.load(r); }
        catch (IllegalArgumentException invalid) { throw new IOException("Malformed B01 file",invalid); }
        return p;
    }
    private static void write(Path file,Properties p) throws IOException {
        Files.createDirectories(file.getParent());
        StringWriter text=new StringWriter(); p.store(text,"B01 v1; one ER character per MC player/world; Speed in blocks/tick, Aura in blocks");
        Path temporary=Files.createTempFile(file.getParent(),".b01-",".tmp");
        try {
            try (var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                ByteBuffer bytes=StandardCharsets.UTF_8.encode(text.toString()); while(bytes.hasRemaining()) channel.write(bytes); channel.force(true);
            }
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
