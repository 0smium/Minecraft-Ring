package dev.ermc.bridge.preparation;

import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.CapabilitySnapshot.NumericRange;
import dev.ermc.bridge.api.CharacterBinding;
import dev.ermc.bridge.api.HostLevelSnapshot;
import dev.ermc.bridge.api.HostSession;
import dev.ermc.bridge.gameplay.capability.AbilityPolicy;
import dev.ermc.bridge.gameplay.capability.AbilityPolicy.Tier;
import dev.ermc.bridge.gameplay.progression.FileLevelProgressStore;
import dev.ermc.bridge.gameplay.progression.LevelProgress;
import dev.ermc.bridge.gameplay.progression.LevelProgressStore;
import dev.ermc.bridge.gameplay.progression.LevelProgression;
import dev.ermc.bridge.gameplay.progression.LevelProgression.Observation;
import dev.ermc.bridge.gameplay.progression.LevelProgression.Outcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Compiles and exercises the real common-source classes, without MC/native stubs or external libraries. */
public final class B01PreparationCheck {
    private static final CharacterBinding A = new CharacterBinding(id(1), id(10), id(100));
    private static final CharacterBinding B = new CharacterBinding(id(2), id(10), id(200));
    private static int checks;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        if (args.length > 1 && args[1].equals("--restore")) {
            run("fresh JVM restores separate character entitlements", () -> restore(root.resolve("demo")));
            return;
        }
        run("loading/unavailable/disconnect never initialize a baseline", () -> availability(root.resolve("states")));
        run("existing character baseline, duplicate and out-of-order samples", () -> ordering(root.resolve("order")));
        run("rollback/recovery cannot grant the same levels twice", () -> rollback(root.resolve("rollback")));
        run("reconnect, stale sessions and delayed disconnects", () -> reconnect(root.resolve("reconnect")));
        run("authenticated players, campaigns and characters stay separate", () -> isolation(root.resolve("isolation")));
        run("failed persistence remains retryable and never resets progress", () -> persistenceFailure(root.resolve("failure")));
        run("stored curves remain stable after defaults change", () -> storedCurve(root.resolve("curve")));
        run("corrupt/versioned/misbound/oversized saves fail closed", () -> corruptSave(root.resolve("corrupt")));
        run("arithmetic overflow cannot commit or consume a sequence", () -> overflow(root.resolve("overflow")));
        run("host snapshots reject invalid state/level/sequence", B01PreparationCheck::invalidSnapshots);
        run("locked, hidden and visible skills all enforce the same gate", B01PreparationCheck::lockedSkills);
        run("current rank clamps settings and rejects unsupported config", B01PreparationCheck::settings);
        run("reload clamps old settings and rejects stale policy requests", B01PreparationCheck::reload);
        run("rank removal is rejected until an explicit migration", B01PreparationCheck::invalidPolicy);
        run("policy, rank and settings views cannot be mutated externally", B01PreparationCheck::immutablePolicy);
        run("SIMULATED two-player source-to-policy preparation", () -> demo(root.resolve("demo")));
        System.out.println("PASS: " + checks + " scenario groups; no Minecraft, Elden Ring or client adapter launched.");
    }

    private static void availability(Path root) throws Exception {
        var store = new FileLevelProgressStore(root);
        var service = new LevelProgression(store, 1, 0);
        var session = session(A, 1);
        var host = new ScriptedHostBackend(session);
        service.connect(session);
        equal(Optional.empty(), host.levelSnapshot());
        outcome(Outcome.NOT_READY, service.observe(A.playerId(), host.loading()));
        outcome(Outcome.NOT_READY, service.observe(A.playerId(), host.unavailable()));
        equal(Optional.empty(), store.load(A));
        HostLevelSnapshot ready = host.level(42);
        equal(ready, host.levelSnapshot().orElseThrow());
        var progress = accepted(service.observe(A.playerId(), ready), Outcome.INITIALIZED);
        equal(42, progress.baselineLevel());
        equal(0, progress.earnedPoints());
        host.disconnect();
        equal(Optional.empty(), host.levelSnapshot());
    }

    private static void ordering(Path root) throws Exception {
        var service = new LevelProgression(new FileLevelProgressStore(root), 2, 3);
        var session = session(A, 2);
        service.connect(session);
        equal(3, accepted(service.observe(A.playerId(), ready(session, 3, 100)), Outcome.INITIALIZED).earnedPoints());
        equal(7, accepted(service.observe(A.playerId(), ready(session, 5, 102)), Outcome.ADVANCED).earnedPoints());
        outcome(Outcome.STALE, service.observe(A.playerId(), ready(session, 5, 102)));
        outcome(Outcome.STALE, service.observe(A.playerId(), ready(session, 4, 999)));
        equal(7, accepted(service.observe(A.playerId(), ready(session, 6, 102)), Outcome.UNCHANGED).earnedPoints());
    }

    private static void rollback(Path root) throws Exception {
        var service = new LevelProgression(new FileLevelProgressStore(root), 1, 0);
        var session = session(A, 3);
        service.connect(session);
        service.observe(A.playerId(), ready(session, 0, 10));
        equal(2, accepted(service.observe(A.playerId(), ready(session, 1, 12)), Outcome.ADVANCED).earnedPoints());
        equal(2, accepted(service.observe(A.playerId(), ready(session, 2, 10)), Outcome.LOWER_LEVEL).earnedPoints());
        equal(2, accepted(service.observe(A.playerId(), ready(session, 3, 12)), Outcome.UNCHANGED).earnedPoints());
        equal(3, accepted(service.observe(A.playerId(), ready(session, 4, 13)), Outcome.ADVANCED).earnedPoints());
    }

    private static void reconnect(Path root) throws Exception {
        var service = new LevelProgression(new FileLevelProgressStore(root), 1, 0);
        var first = session(A, 4);
        var next = session(A, 5);
        service.connect(first);
        service.observe(A.playerId(), ready(first, 20, 10));
        service.observe(A.playerId(), ready(first, 21, 12));
        service.connect(first);
        outcome(Outcome.STALE, service.observe(A.playerId(), ready(first, 20, 999)));
        service.connect(next);
        equal(false, service.disconnect(A.playerId(), first.sessionId()));
        outcome(Outcome.WRONG_SESSION, service.observe(A.playerId(), ready(first, 100, 999)));
        equal(2, accepted(service.observe(A.playerId(), ready(next, 0, 12)), Outcome.UNCHANGED).earnedPoints());
        var another = new CharacterBinding(A.playerId(), A.campaignId(), id(999));
        expect(IllegalArgumentException.class, () -> service.connect(new HostSession(another, next.sessionId())));
        equal(true, service.disconnect(A.playerId(), next.sessionId()));
        outcome(Outcome.UNBOUND, service.observe(A.playerId(), ready(next, 1, 13)));
    }

    private static void isolation(Path root) throws Exception {
        var store = new FileLevelProgressStore(root);
        var service = new LevelProgression(store, 1, 0);
        var a = session(A, 6);
        var b = session(B, 7);
        service.connect(a);
        service.connect(b);
        outcome(Outcome.WRONG_SESSION, service.observe(B.playerId(), ready(a, 0, 10)));
        equal(Optional.empty(), store.load(A));
        equal(Optional.empty(), store.load(B));
        service.observe(A.playerId(), ready(a, 0, 10));
        service.observe(A.playerId(), ready(a, 1, 12));
        equal(0, accepted(service.observe(B.playerId(), ready(b, 0, 90)), Outcome.INITIALIZED).earnedPoints());
        var otherCharacter = new CharacterBinding(A.playerId(), A.campaignId(), id(101));
        var otherCampaign = new CharacterBinding(A.playerId(), id(11), A.characterId());
        for (CharacterBinding binding : List.of(otherCharacter, otherCampaign)) {
            var separate = new HostSession(binding, UUID.randomUUID());
            service.connect(separate);
            equal(0, accepted(service.observe(A.playerId(), ready(separate, 0, 80)), Outcome.INITIALIZED).earnedPoints());
        }
        var back = session(A, 8);
        service.connect(back);
        equal(2, accepted(service.observe(A.playerId(), ready(back, 0, 12)), Outcome.UNCHANGED).earnedPoints());
        equal(0, store.load(B).orElseThrow().earnedPoints());
        var wrongStore = new LevelProgressStore() {
            @Override public Optional<LevelProgress> load(CharacterBinding binding) throws IOException { return store.load(B); }
            @Override public void save(LevelProgress progress) { throw new AssertionError("Must not write another character"); }
        };
        var wrong = new LevelProgression(wrongStore, 1, 0);
        wrong.connect(a);
        expect(IOException.class, () -> wrong.observe(A.playerId(), ready(a, 0, 50)));
    }

    private static void persistenceFailure(Path root) throws Exception {
        var file = new FileLevelProgressStore(root);
        var fault = new FailingStore(file);
        var service = new LevelProgression(fault, 1, 0);
        var session = session(A, 9);
        service.connect(session);
        fault.failSave = true;
        var initial = ready(session, 0, 10);
        expect(IOException.class, () -> service.observe(A.playerId(), initial));
        equal(Optional.empty(), file.load(A));
        accepted(service.observe(A.playerId(), initial), Outcome.INITIALIZED);
        var upgrade = ready(session, 1, 12);
        fault.failSave = true;
        expect(IOException.class, () -> service.observe(A.playerId(), upgrade));
        equal(0, file.load(A).orElseThrow().earnedPoints());
        equal(2, accepted(service.observe(A.playerId(), upgrade), Outcome.ADVANCED).earnedPoints());
        var reloaded = new LevelProgression(fault, 1, 0);
        reloaded.connect(session);
        fault.failLoad = true;
        expect(IOException.class, () -> reloaded.observe(A.playerId(), upgrade));
        equal(2, accepted(reloaded.observe(A.playerId(), upgrade), Outcome.UNCHANGED).earnedPoints());
    }

    private static void storedCurve(Path root) throws Exception {
        var first = new LevelProgression(new FileLevelProgressStore(root), 2, 3);
        var session = session(A, 10);
        first.connect(session);
        first.observe(A.playerId(), ready(session, 0, 10));
        first.observe(A.playerId(), ready(session, 1, 12));
        var reloaded = new LevelProgression(new FileLevelProgressStore(root), 99, 99);
        reloaded.connect(session);
        var progress = accepted(reloaded.observe(A.playerId(), ready(session, 0, 13)), Outcome.ADVANCED);
        equal(9, progress.earnedPoints());
        equal(2, progress.pointsPerLevel());
        equal(3, progress.startingPoints());
    }

    private static void corruptSave(Path root) throws Exception {
        var store = new FileLevelProgressStore(root);
        store.save(new LevelProgress(A, 10, 12, 1, 0));
        Path path = root.resolve(A.campaignId().toString()).resolve(A.playerId().toString())
                .resolve(A.characterId() + ".properties");
        String valid = Files.readString(path);
        List<String> invalid = List.of(valid.replace("version=1", "version=2"),
                valid.replace("highest=12", "highest=9"), valid.replace(A.characterId().toString(), id(999).toString()),
                valid + "unexpected=1\n", "x".repeat(4097), "bad=\\uZZZZ\n");
        for (String broken : invalid) {
            Files.writeString(path, broken);
            var service = new LevelProgression(store, 1, 0);
            var session = session(A, 11);
            service.connect(session);
            expect(IOException.class, () -> service.observe(A.playerId(), ready(session, 0, 50)));
            equal(broken, Files.readString(path));
        }
        Files.writeString(path, valid);
        equal(2, store.load(A).orElseThrow().earnedPoints());
    }

    private static void overflow(Path root) throws Exception {
        var store = new FileLevelProgressStore(root);
        var service = new LevelProgression(store, Integer.MAX_VALUE, 0);
        var session = session(A, 12);
        service.connect(session);
        service.observe(A.playerId(), ready(session, 0, 1));
        expect(ArithmeticException.class, () -> service.observe(A.playerId(), ready(session, 1, 3)));
        equal(0, store.load(A).orElseThrow().earnedPoints());
        equal(Integer.MAX_VALUE, accepted(service.observe(A.playerId(), ready(session, 1, 2)), Outcome.ADVANCED).earnedPoints());
    }

    private static void invalidSnapshots() throws Exception {
        var session = session(A, 13);
        expect(IllegalArgumentException.class, () -> ready(session, -1, 10));
        expect(IllegalArgumentException.class, () -> ready(session, 0, 0));
        expect(IllegalArgumentException.class,
                () -> new HostLevelSnapshot(session, 0, HostLevelSnapshot.State.LOADING, 10));
    }

    private static void lockedSkills() throws Exception {
        for (boolean visible : List.of(true, false)) {
            var locked = policy(1, visible, 1.20).snapshot(A, Map.of());
            for (Ability ability : List.of(Ability.SPEED, Ability.AURA)) {
                equal(visible, locked.abilities().get(ability).visible());
                equal(false, locked.abilities().get(ability).unlocked());
                expect(IllegalStateException.class, () -> locked.authorize(ability, 1, "ground", Map.of()));
            }
        }
        var a = policy(1, false, 1.20).snapshot(A, Map.of(Ability.SPEED, 2));
        var b = policy(1, false, 1.20).snapshot(B, Map.of(Ability.AURA, 1));
        equal(true, a.abilities().get(Ability.SPEED).unlocked());
        equal(false, b.abilities().get(Ability.SPEED).unlocked());
        equal(false, a.abilities().get(Ability.AURA).unlocked());
        equal(true, b.abilities().get(Ability.AURA).unlocked());
    }

    private static void settings() throws Exception {
        var view = policy(1, true, 1.20).snapshot(A, Map.of(Ability.SPEED, 2, Ability.AURA, 1));
        equal(Map.of("multiplier", 1.20), view.authorize(Ability.SPEED, 1, "ground", Map.of("multiplier", 99.0)));
        equal(Map.of("multiplier", 1.0), view.authorize(Ability.SPEED, 1, "ground", Map.of("multiplier", -99.0)));
        equal(Map.of("range", 2.5), view.authorize(Ability.AURA, 1, "single_target", Map.of("range", 99.0)));
        expect(IllegalArgumentException.class, () -> view.authorize(Ability.SPEED, 1, "flight", Map.of("multiplier", 1.1)));
        expect(IllegalArgumentException.class, () -> view.authorize(Ability.SPEED, 1, "ground", Map.of()));
        expect(IllegalArgumentException.class, () -> view.authorize(Ability.SPEED, 1, "ground", Map.of("multiplier", 1.1, "bypass", 1.0)));
        for (double invalid : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            expect(IllegalArgumentException.class, () -> view.authorize(Ability.SPEED, 1, "ground", Map.of("multiplier", invalid)));
        }
    }

    private static void reload() throws Exception {
        var old = policy(1, true, 1.20).snapshot(A, Map.of(Ability.SPEED, 2));
        var current = policy(2, true, 1.10).snapshot(A, Map.of(Ability.SPEED, 2));
        expect(IllegalStateException.class, () -> current.authorize(Ability.SPEED, 1, "ground", Map.of("multiplier", 1.20)));
        equal(Map.of("multiplier", 1.10), current.authorize(Ability.SPEED, 2, "ground", Map.of("multiplier", 1.20)));
        equal(1.20, old.abilities().get(Ability.SPEED).numericLimits().get("multiplier").maximum());
        var refunded = policy(2, true, 1.10).snapshot(A, Map.of());
        expect(IllegalStateException.class, () -> refunded.authorize(Ability.SPEED, 2, "ground", Map.of("multiplier", 1.10)));
    }

    private static void invalidPolicy() throws Exception {
        expect(IllegalArgumentException.class, () -> policy(0, true, 1.20));
        expect(IllegalArgumentException.class, () -> new NumericRange(2, 1));
        expect(IllegalArgumentException.class, () -> new NumericRange(1, Double.NaN));
        expect(IllegalArgumentException.class, () -> new Tier(Set.of(), Map.of()));
        expect(IllegalArgumentException.class, () -> new AbilityPolicy(1, true, Map.of()));
        var policy = policy(1, true, 1.20);
        expect(IllegalArgumentException.class, () -> policy.snapshot(A, Map.of(Ability.SPEED, -1)));
        expect(IllegalArgumentException.class, () -> policy.snapshot(A, Map.of(Ability.SPEED, 3)));
        equal(2, policy.snapshot(A, Map.of(Ability.SPEED, 2)).abilities().get(Ability.SPEED).rank());
    }

    private static void immutablePolicy() throws Exception {
        Set<String> modes = new HashSet<>(Set.of("ground"));
        Map<String, NumericRange> limits = new HashMap<>(Map.of("multiplier", new NumericRange(1, 1.1)));
        Tier tier = new Tier(modes, limits);
        modes.add("flight");
        limits.put("multiplier", new NumericRange(1, 100));
        List<Tier> levels = new ArrayList<>(List.of(tier));
        Map<Ability, List<Tier>> rules = new HashMap<>();
        rules.put(Ability.SPEED, levels);
        rules.put(Ability.AURA, List.of(new Tier(Set.of("single_target"), Map.of("range", new NumericRange(0, 2.5)))));
        var policy = new AbilityPolicy(1, true, rules);
        levels.clear();
        rules.clear();
        Map<Ability, Integer> ranks = new HashMap<>();
        ranks.put(Ability.SPEED, 1);
        var view = policy.snapshot(A, ranks);
        ranks.clear();
        equal(Set.of("ground"), view.abilities().get(Ability.SPEED).allowedModes());
        equal(Map.of("multiplier", 1.1), view.authorize(Ability.SPEED, 1, "ground", Map.of("multiplier", 100.0)));
        expect(UnsupportedOperationException.class, () -> view.abilities().clear());
        expect(UnsupportedOperationException.class, () -> tier.allowedModes().add("flight"));
        expect(UnsupportedOperationException.class, () -> view.authorize(Ability.SPEED, 1, "ground", Map.of("multiplier", 1.0)).clear());
    }

    private static void demo(Path root) throws Exception {
        var service = new LevelProgression(new FileLevelProgressStore(root), 1, 0);
        var a = new ScriptedHostBackend(session(A, 20));
        var b = new ScriptedHostBackend(session(B, 21));
        service.connect(session(A, 20));
        service.connect(session(B, 21));
        service.observe(A.playerId(), a.level(10));
        service.observe(B.playerId(), b.level(20));
        var pa = service.observe(A.playerId(), a.level(12)).progress().orElseThrow();
        var pb = service.observe(B.playerId(), b.level(21)).progress().orElseThrow();
        equal(2, pa.earnedPoints());
        equal(1, pb.earnedPoints());
        // Learned ranks are a fixture, not a purchase implementation or automatic spending of these points.
        var va = policy(1, true, 1.20).snapshot(A, Map.of(Ability.SPEED, 2));
        var vb = policy(1, true, 1.20).snapshot(B, Map.of(Ability.AURA, 1));
        System.out.println("SIMULATED: A earned=" + pa.earnedPoints() + ", Speed="
                + va.authorize(Ability.SPEED, 1, "ground", Map.of("multiplier", 99.0))
                + "; B earned=" + pb.earnedPoints() + ", Aura="
                + vb.authorize(Ability.AURA, 1, "single_target", Map.of("range", 99.0)));
    }

    private static void restore(Path root) throws Exception {
        var service = new LevelProgression(new FileLevelProgressStore(root), 1, 0);
        var a = session(A, 30);
        var b = session(B, 31);
        service.connect(a);
        service.connect(b);
        equal(2, accepted(service.observe(A.playerId(), ready(a, 0, 12)), Outcome.UNCHANGED).earnedPoints());
        equal(1, accepted(service.observe(B.playerId(), ready(b, 0, 21)), Outcome.UNCHANGED).earnedPoints());
    }

    private static AbilityPolicy policy(long revision, boolean showLocked, double speedMaximum) {
        return new AbilityPolicy(revision, showLocked, Map.of(
                Ability.SPEED, List.of(new Tier(Set.of("ground"), Map.of("multiplier", new NumericRange(1, 1.1))),
                        new Tier(Set.of("ground"), Map.of("multiplier", new NumericRange(1, speedMaximum)))),
                Ability.AURA, List.of(new Tier(Set.of("single_target"), Map.of("range", new NumericRange(0, 2.5))),
                        new Tier(Set.of("single_target"), Map.of("range", new NumericRange(0, 3.0))))));
    }

    private static UUID id(long value) { return new UUID(0, value); }
    private static HostSession session(CharacterBinding binding, long value) { return new HostSession(binding, id(1000 + value)); }
    private static HostLevelSnapshot ready(HostSession session, long sequence, int level) {
        return new HostLevelSnapshot(session, sequence, HostLevelSnapshot.State.READY, level);
    }

    private static void run(String name, Check check) throws Exception {
        check.run();
        checks++;
        System.out.println("PASS: " + name);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static LevelProgress accepted(Observation result, Outcome outcome) {
        equal(outcome, result.outcome());
        return result.progress().orElseThrow();
    }

    private static void outcome(Outcome expected, Observation result) {
        equal(expected, result.outcome());
        equal(Optional.empty(), result.progress());
    }

    private static void expect(Class<? extends Throwable> type, Check check) throws Exception {
        try { check.run(); }
        catch (Exception failure) {
            if (type.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static final class FailingStore implements LevelProgressStore {
        private final LevelProgressStore delegate;
        boolean failSave;
        boolean failLoad;
        FailingStore(LevelProgressStore delegate) { this.delegate = delegate; }
        @Override public Optional<LevelProgress> load(CharacterBinding binding) throws IOException {
            if (failLoad) { failLoad = false; throw new IOException("Injected read failure"); }
            return delegate.load(binding);
        }
        @Override public void save(LevelProgress progress) throws IOException {
            if (failSave) { failSave = false; throw new IOException("Injected write failure"); }
            delegate.save(progress);
        }
    }
}
