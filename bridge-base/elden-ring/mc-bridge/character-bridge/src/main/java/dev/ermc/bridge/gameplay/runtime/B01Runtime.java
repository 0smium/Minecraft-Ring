package dev.ermc.bridge.gameplay.runtime;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.api.*;
import dev.ermc.bridge.gameplay.capability.AbilityPolicy;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.ermc.bridge.gameplay.progression.*;
import dev.ermc.bridge.gameplay.skills.SkillsBridge;
import dev.ermc.bridge.link.*;
import java.io.IOException;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single-host B01 integration. The integrated server owns identity, level entitlement and grants. */
public final class B01Runtime {
    private static final Logger LOG = LoggerFactory.getLogger("erbridge");
    private static B01Runtime active;
    private final MinecraftServer server;
    private final B01Files files;
    private B01Files.Settings settings;
    private AbilityPolicy policy;
    private LevelProgression progression;
    private B01Files.Binding binding;
    private boolean bindingLoaded;
    private HostSession session;
    private ServerPlayer owner;
    private long hostStart, lastFrame = -1, frameChangedAt;
    private HostProgressionSample currentSample;
    private LevelProgress progress;
    private SkillsBridge.Status skills;
    private String fault, status = "等待角色绑定";
    private CapabilitySnapshot grants;
    private int ticks;
    private long lastError;
    private final Map<UUID, B01Payload> sent = new HashMap<>();

    private B01Runtime(MinecraftServer server) {
        this.server = server;
        files = new B01Files(server.getWorldPath(LevelResource.ROOT).resolve("data/erbridge"));
        try { install(files.loadSettings(true)); }
        catch (Exception e) { fail(e); }
    }
    public static Optional<CapabilitySnapshot> snapshotFor(ServerPlayer player) {
        B01Runtime runtime = active;
        return runtime != null && runtime.owner == player && runtime.fault == null ? Optional.ofNullable(runtime.grants) : Optional.empty();
    }
    public static boolean challengeFor(ServerPlayer player) {
        B01Runtime runtime = active;
        return runtime == null || runtime.owner != player || runtime.settings == null || runtime.settings.challenge();
    }
    private void install(B01Files.Settings next) {
        closeSession();
        settings = next; policy = next.policy();
        progression = new LevelProgression(new FileLevelProgressStore(files.root().resolve("levels")), next.pointsPerLevel(), next.startingPoints());
        binding = null; bindingLoaded = false; owner = null; fault = null;
    }
    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(B01Payload.TYPE, B01Payload.CODEC);
        SkillsBridge.initialize(player -> { if (active != null && player.getServer() == active.server) active.sent.remove(player.getUUID()); });
        BridgeLifecycle.HOST_WORLD_READY.register(server -> {
            if (TerrainManager.isBridgeWorld()) active = new B01Runtime(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { if (active != null && active.server == server) active = null; });
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (active != null && active.server == server) active.tick(); });
        BridgeLifecycle.PLAYER_DISCONNECTED.register(player -> {
            MinecraftServer server = player.getServer();
            if (active != null && active.server == server) {
                active.sent.remove(player.getUUID());
                if (player == active.owner) { active.closeSession(); active.owner = null; active.binding = null; active.bindingLoaded = false; }
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("erbridge").then(Commands.literal("progression")
                        .executes(c -> command(c.getSource(), "status"))
                        .then(Commands.literal("status").executes(c -> command(c.getSource(), "status")))
                        .then(Commands.literal("skills").executes(c -> command(c.getSource(), "skills")))
                        .then(Commands.literal("bind").executes(c -> command(c.getSource(), "bind")))
                        .then(Commands.literal("challenge").executes(c -> command(c.getSource(), "challenge")))
                        .then(Commands.literal("free").executes(c -> command(c.getSource(), "free")))
                        .then(Commands.literal("refund").executes(c -> command(c.getSource(), "refund")))
                        .then(Commands.literal("catalog").executes(c -> command(c.getSource(), "catalog")))
                        .then(Commands.literal("migrate").executes(c -> command(c.getSource(), "migrate")))
                        .then(Commands.literal("preset")
                                .executes(c -> command(c.getSource(), "preset_preview"))
                                .then(Commands.literal("preview").executes(c -> command(c.getSource(), "preset_preview")))
                                .then(Commands.literal("apply").then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> { AbilityCatalog.instance().presets().keySet().forEach(b::suggest); return b.buildFuture(); })
                                        .executes(c -> command(c.getSource(), "preset_apply:" + StringArgumentType.getString(c, "name")))))
                                .then(Commands.literal("clear").executes(c -> command(c.getSource(), "preset_clear"))))
                        .then(Commands.literal("reload").executes(c -> command(c.getSource(), "reload"))))));
    }
    private ServerPlayer localOwner() {
        var profile = server.getSingleplayerProfile();
        return server.isDedicatedServer() || profile == null ? null : server.getPlayerList().getPlayer(profile.getId());
    }
    private void closeSession() {
        if (session != null && progression != null) progression.disconnect(session.binding().playerId(), session.sessionId());
        session = null; progress = null; skills = null; grants = null;
        currentSample = null; lastFrame = -1; frameChangedAt = 0; hostStart = 0;
    }
    private void tick() {
        ticks++;
        try { update(); }
        catch (Exception e) { fail(e); }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean challenge = settings == null || settings.challenge(); // corrupt configuration cannot grant free access
            boolean isOwner = player == owner;
            B01Payload packet = new B01Payload(player.getUUID(), challenge, isOwner && fault == null ? grants : null,
                    isOwner && currentSample != null ? currentSample.level() : 0,
                    isOwner && progress != null ? progress.earnedPoints() : 0,
                    isOwner && skills != null ? skills.spentPoints() : 0,
                    isOwner && skills != null ? skills.remainingPoints() : 0,
                    isOwner ? fault == null ? status : "B01 数据错误；查看日志并 reload" : "B01 仅绑定本机法环拥有者，访客未授权");
            if (ticks % 20 == 0 || !packet.equals(sent.get(player.getUUID()))) {
                if (ServerPlayNetworking.canSend(player, B01Payload.TYPE)) ServerPlayNetworking.send(player, packet);
                sent.put(player.getUUID(), packet);
            }
        }
    }
    private void update() throws IOException {
        ServerPlayer player = localOwner();
        if (owner != player) {
            closeSession(); owner = player; binding = null; bindingLoaded = false;
        }
        grants = null; currentSample = null;
        if (settings == null || owner == null) return;
        fault = null;
        if (!bindingLoaded) {
            binding = files.loadBinding(owner.getUUID(), settings.campaign()).orElse(null);
            bindingLoaded = true;
        }
        ErLink link = ErLink.get();
        if (!link.poll()) { closeSession(); status = "法环未连接；能力暂时锁定"; return; }
        long start = link.hostStartMs();
        if (start <= 0) { status = "等待法环连接身份"; return; }
        if (hostStart != start) { closeSession(); hostStart = start; }
        var optional = link.readHostProgression();
        if (optional.isEmpty()) { status = "原生等级采样不可用；检查 HPRG DLL"; return; }
        HostProgressionSample sample = optional.get();
        GameState game = new GameState();
        if (!link.snapshot(game) || Math.abs(game.frame - sample.frame()) > 240 || sample.hostLife() != link.hostLife()) {
            status = "等待同一角色生命周期的等级采样"; return;
        }
        long now = System.nanoTime();
        if (sample.frame() > lastFrame) { lastFrame = sample.frame(); frameChangedAt = now; }
        if (sample.frame() < lastFrame || frameChangedAt == 0 || now - frameChangedAt > 2_000_000_000L) {
            status = "等级采样已过期；能力暂时锁定"; return;
        }
        if (!sample.ready() || game.has(Protocol.STATE_HOST_BUSY) || game.has(Protocol.STATE_PLAYER_DEAD)) {
            status = "角色加载或死亡中；暂停发点和能力"; return;
        }
        currentSample = sample;
        if (binding == null) { status = "未绑定：/erbridge progression bind（一个 MC 世界仅绑定当前法环角色）"; return; }
        if (!binding.matches(sample.fingerprint(), sample.name())) {
            status = "角色名称与绑定不符；已锁定。其他角色请使用新 MC 世界"; return;
        }
        if (session == null) {
            session = new HostSession(binding.identity(), UUID.randomUUID()); progression.connect(session);
        }
        var result = progression.observe(owner.getUUID(), new HostLevelSnapshot(session, sample.frame(), HostLevelSnapshot.State.READY, sample.level()));
        if (result.progress().isPresent()) progress = result.progress().get();
        if (progress == null) { status = "等待已持久化的等级记录"; return; }
        if (progress.earnedPoints() > 100000) throw new IOException("Stored point curve exceeds the supported B01 budget");
        // Always retry cumulative reconciliation, including duplicate samples and framework reload/login.
        var reconciled = SkillsBridge.reconcile(owner, progress);
        if (reconciled.isEmpty()) { skills = null; status = "技能树依赖/资源不可用；请用 B01 启动配置"; return; }
        skills = reconciled.get();
        if (skills.migrationRequired()) {
            status = "旧树成本已改变；能力暂停。progression migrate 将备份旧节点并全额退款，等级额度不变"; return;
        }
        if (skills.sourcePoints() != progress.earnedPoints() || skills.totalPoints() != progress.earnedPoints() + skills.testPoints()
                || skills.spentPoints() < 0 || skills.spentPoints() > skills.totalPoints() || skills.remainingPoints() != skills.totalPoints() - skills.spentPoints()) {
            skills = null;
            status = "技能点账目尚未同步；能力暂时锁定"; return;
        }
        grants = policy.snapshot(binding.identity(), skills.learnedRanks());
        status = (settings.challenge() ? "挑战" : "自由") + " | " + sample.name() + " Lv." + sample.level()
                + " | 技能点 " + skills.remainingPoints() + "/" + skills.totalPoints()
                + (skills.testPoints() > 0 ? "（真实 " + progress.earnedPoints() + " + 测试 " + skills.testPoints() + " · " + skills.preset() + "）" : "")
                + " | /erbridge progression skills";
    }
    private void fail(Exception e) {
        fault = e.toString(); grants = null; currentSample = null; progress = null; skills = null;
        if (System.nanoTime() - lastError > 5_000_000_000L || lastError == 0) {
            lastError = System.nanoTime(); LOG.error("B01 failed closed: {}", files.configPath(), e);
        }
    }
    private static int command(CommandSourceStack source, String operation) {
        B01Runtime runtime = active;
        if (runtime == null || source.getServer() != runtime.server) return error(source,"B01 仅在 ER Bridge 世界可用");
        ServerPlayer player = source.getPlayer();
        if (player == null || player != runtime.localOwner()) return error(source,"只有本机法环拥有者可以操作 B01");
        try { return runtime.execute(source, player, operation); }
        catch (Exception e) { LOG.warn("B01 command {} rejected", operation, e); return error(source,e.getMessage() == null ? e.toString() : e.getMessage()); }
    }
    private int execute(CommandSourceStack source, ServerPlayer player, String operation) throws IOException {
        if (operation.equals("catalog")) {
            for (var group : AbilityCatalog.instance().categories().entrySet()) {
                reply(source, group.getValue() + "：" + String.join("；", AbilityCatalog.instance().entries().stream().filter(e -> e.category().equals(group.getKey()))
                        .map(e -> e.name() + " " + e.ranks().stream().map(r -> Integer.toString(r.cost())).toList() + " 点").toList()));
            }
            reply(source, "76 点可学全部基础；116 点广修并精通 Speed/Aura；满配 232 点。HUD/装饰免费，其余未适配行为模块在挑战模式关闭。"); return 1;
        }
        if (operation.equals("preset_preview")) {
            for (var preset : AbilityCatalog.instance().presets().values()) reply(source, preset.id() + " · " + preset.name() + " | 测试额度/实际消费 " + preset.budget() + "/"
                    + AbilityCatalog.instance().cost(preset.ranks()) + " | " + preset.ranks());
            reply(source, "apply <名称> 暂存原构筑，使用 Skills 正式购买；不改 ER 等级、不启用模块。clear 撤销测试点并恢复原构筑。"); return 1;
        }
        // Recovery remains available even if an interrupted preset currently fails closed.
        if (operation.equals("migrate") || operation.equals("preset_clear")) {
            if (binding == null) throw new IOException("先绑定角色");
            LevelProgress saved = new FileLevelProgressStore(files.root().resolve("levels")).load(binding.identity()).orElseThrow(() -> new IOException("缺少真实等级记录"));
            reply(source, operation.equals("migrate") ? SkillsBridge.migrate(player, saved) : SkillsBridge.clearPreset(player, saved));
            fault = null; sent.clear(); tick(); return 1;
        }
        if (operation.equals("status")) {
            reply(source, fault == null ? status : "B01 错误：" + fault);
            reply(source,"配置：" + files.configPath());
            if (binding != null) reply(source,"角色 UUID：" + binding.identity().characterId() + "；姓名仅为弱观察值，同名重建需要新世界");
            if (grants != null) for (var entry : grants.abilities().entrySet()) reply(source,entry.getKey()+" rank="+entry.getValue().rank()+" "+entry.getValue().numericLimits());
            return 1;
        }
        if (operation.equals("reload")) {
            B01Files.Settings next = files.loadSettings(false);
            if (settings != null && !next.campaign().equals(settings.campaign())) throw new IOException("禁止通过 reload 替换 campaign");
            next = next.revision(Math.incrementExact(Math.max(next.revision(),settings == null ? 0 : settings.revision())));
            if (binding != null && skills != null) next.policy().snapshot(binding.identity(),skills.learnedRanks());
            // Persist complete validated policy before publishing the new revision.
            files.saveSettings(next); install(next); sent.clear(); tick(); reply(source,"B01 配置已重载；已有角色保留其原发点曲线"); return 1;
        }
        if (fault != null || settings == null) throw new IOException("先修复配置/存储错误并执行 reload");
        if (operation.startsWith("preset_apply:")) {
            update();
            if (!settings.challenge()) throw new IOException("先 progression challenge；测试预设始终受技能限制");
            if (binding == null || progress == null || grants == null) throw new IOException("需要已绑定、已迁移且同步正常的角色");
            reply(source, SkillsBridge.applyPreset(player, progress, operation.substring("preset_apply:".length())));
            sent.clear(); tick(); return 1;
        }
        if (operation.equals("bind")) {
            if (binding != null) throw new IOException("本世界已有角色绑定；不会覆盖。换角色请使用新 MC 世界");
            update();
            if (currentSample == null || !currentSample.ready()) throw new IOException("先加载存档并等待有效法环等级");
            B01Files.Binding candidate = new B01Files.Binding(new CharacterBinding(player.getUUID(),settings.campaign(),UUID.randomUUID()),currentSample.fingerprint(),currentSample.name());
            files.createBinding(candidate); binding = candidate; closeSession();
            reply(source,"已绑定 " + candidate.observedName() + "；已有等级作为起点，之后每升一级发点。启用限制：/erbridge progression challenge");
        } else if (operation.equals("challenge") || operation.equals("free")) {
            boolean challenge = operation.equals("challenge");
            if (challenge && (!SkillsBridge.available() || binding == null)) throw new IOException("挑战模式需要 B01 技能树依赖和显式角色绑定");
            B01Files.Settings next = settings.mode(challenge); files.saveSettings(next); settings = next; policy = next.policy();
            reply(source,challenge ? "挑战模式：六类 14 项由技能树授权；其余未适配行为模块关闭，HUD/装饰免费" : "自由模式：解除成长限制，测试预设仍可用 preset clear 撤销");
        } else if (operation.equals("skills")) {
            if (binding == null || progress == null || !SkillsBridge.openScreen(player)) throw new IOException("请先绑定角色并等待技能树同步");
        } else if (operation.equals("refund")) {
            if (binding == null || !SkillsBridge.resetSkills(player)) throw new IOException("技能树尚不可用");
            reply(source,"已通过技能树退还本树全部已购节点；将正常关闭被撤销的能力");
        }
        sent.clear(); tick(); return 1;
    }
    private static void reply(CommandSourceStack source,String message) { source.sendSuccess(()->Component.literal("[ER Bridge] "+message),false); }
    private static int error(CommandSourceStack source,String message) { source.sendFailure(Component.literal("[ER Bridge] "+message)); return 0; }
}
