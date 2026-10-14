package dev.ermc.bridge.gameplay.skills;

import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.gameplay.progression.LevelProgress;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional server-side adapter; Pufferfish owns purchases, refunds and their persistence. */
public final class SkillsBridge {
    public static final String MOD_ID = "puffish_skills";
    public static final String SUPPORTED_VERSION = "0.19.2";
    public static final String CATEGORY_ID = "erbridge:abilities";
    private static final Logger LOG = LoggerFactory.getLogger("erbridge");
    private static Backend backend;

    private SkillsBridge() {}

    public record Status(int sourcePoints, int totalPoints, int spentPoints, int remainingPoints,
                         Map<Ability, Integer> learnedRanks, int testPoints, boolean migrationRequired, String preset) {
        public Status { learnedRanks = Map.copyOf(learnedRanks); }
    }

    public interface Backend {
        void register(Consumer<ServerPlayer> changed);
        Optional<Status> reconcile(ServerPlayer player, LevelProgress progress);
        Optional<Status> snapshot(ServerPlayer player);
        boolean openScreen(ServerPlayer player);
        boolean resetSkills(ServerPlayer player);
        String migrate(ServerPlayer player, LevelProgress progress);
        String applyPreset(ServerPlayer player, LevelProgress progress, String id);
        String clearPreset(ServerPlayer player, LevelProgress progress);
    }

    /** Call once from common initialization; changed runs only on the player's server thread. */
    public static boolean initialize(Consumer<ServerPlayer> changed) {
        Objects.requireNonNull(changed, "changed");
        if (backend != null) return true;
        var mod = FabricLoader.getInstance().getModContainer(MOD_ID);
        if (mod.isEmpty()) {
            LOG.info("Skills integration unavailable: Pufferfish's Skills is not installed");
            return false;
        }
        String version = mod.get().getMetadata().getVersion().getFriendlyString();
        if (!SUPPORTED_VERSION.equals(version)) {
            LOG.warn("Skills integration unavailable: unsupported Pufferfish's Skills {}", version);
            return false;
        }
        // The optional build profile contributes this implementation; the default character
        // JAR contains neither it nor any Pufferfish API references.
        try {
            Class<?> adapter = Class.forName("dev.ermc.bridge.gameplay.skills.PuffishSkillsAdapter",
                    true, SkillsBridge.class.getClassLoader());
            Backend candidate = adapter.asSubclass(Backend.class).getConstructor().newInstance();
            candidate.register(changed);
            backend = candidate;
            LOG.info("Skills integration ready: Pufferfish's Skills {}", version);
            return true;
        } catch (ClassNotFoundException e) {
            LOG.warn("Skills integration unavailable: use the B01 character artifact to enable the installed Skills dependency");
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            LOG.error("Skills integration unavailable: optional adapter could not be loaded", e);
        }
        return false;
    }

    public static boolean available() { return backend != null; }

    /** Reapply the existing B01 ledger on login and after every accepted level change. */
    public static Optional<Status> reconcile(ServerPlayer player, LevelProgress progress) {
        requireServerThread(player);
        Objects.requireNonNull(progress, "progress");
        if (!player.getUUID().equals(progress.binding().playerId())) {
            throw new IllegalArgumentException("Skills entitlement belongs to a different player");
        }
        return backend == null ? Optional.empty() : backend.reconcile(player, progress);
    }

    public static Optional<Status> snapshot(ServerPlayer player) {
        requireServerThread(player);
        return backend == null ? Optional.empty() : backend.snapshot(player);
    }

    public static boolean openScreen(ServerPlayer player) {
        requireServerThread(player);
        return backend != null && backend.openScreen(player);
    }

    /** A root-authorized respec uses the framework's real refund and lock callbacks. */
    public static boolean resetSkills(ServerPlayer player) {
        requireServerThread(player);
        return backend != null && backend.resetSkills(player);
    }
    public static String migrate(ServerPlayer player, LevelProgress progress) {
        requireServerThread(player); if (backend == null) throw new IllegalStateException("Skills 不可用");
        return backend.migrate(player, progress);
    }
    public static String applyPreset(ServerPlayer player, LevelProgress progress, String id) {
        requireServerThread(player); if (backend == null) throw new IllegalStateException("Skills 不可用");
        return backend.applyPreset(player, progress, id);
    }
    public static String clearPreset(ServerPlayer player, LevelProgress progress) {
        requireServerThread(player); if (backend == null) throw new IllegalStateException("Skills 不可用");
        return backend.clearPreset(player, progress);
    }

    private static void requireServerThread(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (player.getServer() == null || !player.getServer().isSameThread()) {
            throw new IllegalStateException("Skills operations must run on the player's server thread");
        }
    }
}
