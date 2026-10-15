package dev.ermc.bridge;

import dev.ermc.bridge.api.MovementSubjects;
import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.Protocol;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import org.slf4j.LoggerFactory;

/** Administrator-only vanilla horse trial. No native mount, immortality or custom movement. */
public final class MountRuntime {
    private MountRuntime() {}
    private static final String TAG = "erbridge.b02_test_mount";
    private static final GameState STATE = new GameState();
    private static final List<Horse> RETIRED = new ArrayList<>();
    private static Horse active;
    private static ServerPlayer owner;
    private static long hostStart;
    private static int hostLife, zone;

    public static void initialize() {
        MovementSubjects.register(MountMotion::body);
        BridgeLifecycle.BEFORE_TERRAIN_TICK.register(MountRuntime::beforeTerrainTick);
        BridgeLifecycle.BEFORE_RECALL.register(MountRuntime::beforeRecall);
        PayloadTypeRegistry.playS2C().register(MountPayload.TYPE, MountPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MountPayload.Release.TYPE, MountPayload.Release.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MountPayload.Release.TYPE, (payload, context) -> {
            if (context.player() == owner && LocalHostOwner.isOwner(context.player())) release("Minecraft control released");
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.literal("erbridge").then(Commands.literal("mount").requires(s -> s.hasPermission(2))
                .executes(c -> command(c.getSource(), "status"))
                .then(Commands.literal("summon").executes(c -> command(c.getSource(), "summon")))
                .then(Commands.literal("dismiss").executes(c -> command(c.getSource(), "dismiss")))
                .then(Commands.literal("status").executes(c -> command(c.getSource(), "status"))))));
        ServerLifecycleEvents.SERVER_STARTING.register(s -> { active = null; owner = null; RETIRED.clear(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { release("server stopping"); collectRetired(s); });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> { active = null; owner = null; RETIRED.clear(); });
        BridgeLifecycle.PLAYER_DISCONNECTED.register(player -> {
            if (player == owner) release("owner disconnected");
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity == owner || entity == active) release("rider or horse died");
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Horse horse && horse.getTags().contains(TAG) && horse != active) {
                horse.ejectPassengers();
                horse.setNoAi(true);
                if (!RETIRED.contains(horse)) RETIRED.add(horse);
            }
        });
    }

    /** Runs before TerrainManager can recall/teleport the owner. */
    public static void beforeTerrainTick(MinecraftServer s) {
        if (active != null) {
            ErLink link = ErLink.get();
            var map = CoordMap.get();
            if (owner == null || !LocalHostOwner.isOwner(owner) || !owner.isAlive() || !active.isAlive()
                    || active.isRemoved() || owner.getVehicle() != active || active.getFirstPassenger() != owner
                    || owner.level() != s.overworld() || active.level() != s.overworld()
                    || !TerrainManager.isBridgeWorld() || !link.poll() || !link.snapshot(STATE)
                    || !STATE.has(Protocol.STATE_PLAYER_VALID) || !STATE.has(Protocol.STATE_CAM_OVERRIDDEN) || STATE.has(Protocol.STATE_HOST_BUSY)
                    || link.hostStartMs() != hostStart || link.hostLife() != hostLife || STATE.stageId != zone
                    || map == null || map.zone() != zone || !TerrainManager.recallSettled(0)) release("ride or host session ended");
        }
        collectRetired(s);
    }

    static Entity body(ServerPlayer player) {
        return player == owner && active != null && active.isAlive() && player.getVehicle() == active
            && active.getFirstPassenger() == player ? active : player;
    }

    public static void beforeRecall(ServerPlayer player) { if (player == owner) release("host recall"); }

    private static int command(CommandSourceStack source, String action) {
        ServerPlayer player = source.getPlayer();
        if (player == null || !LocalHostOwner.isOwner(player) || !TerrainManager.isBridgeWorld()
                || player.level() != player.getServer().overworld()) return fail(source, "仅 ER Bridge 的本机主人可测试坐骑。");
        if (action.equals("status")) {
            source.sendSuccess(() -> Component.literal(active != null ? "MC 测试马已骑乘；正常潜行下马，或 /erbridge mount dismiss。"
                : RETIRED.isEmpty() ? "没有 MC 测试马。summon 会借用背包中的一个鞍。"
                : "测试马已下马，装备等待归还；请腾出背包空间。"), false);
            return 1;
        }
        if (action.equals("dismiss")) {
            if (active != null && !active.onGround()) return fail(source, "请先让马落到固定地面再回收；空中仍使用原版摔伤。");
            release("dismiss command"); collectRetired(player.getServer()); return 1;
        }
        beforeTerrainTick(player.getServer());
        if (!dev.ermc.bridge.api.PlayerActions.allowed(player)) return fail(source, "当前状态不能召唤坐骑。");
        if (active != null) return fail(source, "已有测试马，请先下马。");
        if (!RETIRED.isEmpty()) return fail(source, "上一匹测试马的装备尚未归还，请腾出背包空间。");
        ErLink link = ErLink.get();
        var map = CoordMap.get();
        if (!player.isAlive() || player.isPassenger() || !player.onGround() || player.getAbilities().flying
                || player.isInWater() || player.isInLava() || !link.poll() || !link.snapshot(STATE)
                || link.hostStartMs() <= 0 || !STATE.has(Protocol.STATE_PLAYER_VALID)
                || !STATE.has(Protocol.STATE_CAM_OVERRIDDEN) || STATE.has(Protocol.STATE_HOST_BUSY)
                || map == null || map.provisional() || map.zone() != STATE.stageId || !TerrainManager.recallSettled(400))
            return fail(source, "需要在法环已接管的安全固定地面召唤；请退出其他载具/飞行。");
        int saddleSlot = -1;
        for (int i = 0; i < player.getInventory().items.size(); i++)
            if (player.getInventory().getItem(i).is(Items.SADDLE)) { saddleSlot = i; break; }
        if (saddleSlot < 0) return fail(source, "背包需要一个鞍；管理员测试可先 /give @s minecraft:saddle 1。回收时归还。");
        ServerLevel level = player.serverLevel();
        Horse horse = EntityType.HORSE.create(level);
        if (horse == null) return fail(source, "Minecraft 未能创建原版马。");
        horse.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0);
        // Only block collision is tested here: the rider intentionally overlaps its new vehicle.
        if (level.getBlockCollisions(horse, horse.getBoundingBox()).iterator().hasNext())
            return fail(source, "这里放不下马，请移到更宽敞的固定地面。");
        horse.setTamed(true); horse.setOwnerUUID(player.getUUID()); horse.setPersistenceRequired();
        horse.addTag(TAG);
        ItemStack saddle = player.getInventory().removeItem(saddleSlot, 1);
        horse.equipSaddle(saddle, SoundSource.NEUTRAL);
        active = horse; owner = player;
        hostStart = link.hostStartMs(); hostLife = link.hostLife(); zone = STATE.stageId;
        if (!level.addFreshEntity(horse) || !player.startRiding(horse)) {
            release("summon failed"); return fail(source, "未能上马，已回收测试马并归还鞍。");
        }
        publish(player, horse);
        TerrainTravelClearance.invalidate();
        source.sendSuccess(() -> Component.literal("已骑上原版 MC 测试马；方向键移动、Space 蓄力跳跃、潜行下马。"), false);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) { source.sendFailure(Component.literal(message)); return 0; }

    private static void release(String reason) {
        Horse horse = active;
        ServerPlayer rider = owner;
        active = null; owner = null;
        if (horse == null) return;
        TerrainTravelClearance.invalidate();
        if (rider != null) publish(rider, null);
        // Vanilla chooses the dismount position and retains fall damage. No teleport-to-origin.
        horse.ejectPassengers();
        if (horse.isAlive() && !horse.isRemoved()) {
            horse.setNoAi(true);
            if (!returnEquipment(horse, rider)) {
                if (!RETIRED.contains(horse)) RETIRED.add(horse);
            }
        }
        LoggerFactory.getLogger("erbridge").info("B02 test mount released: {}", reason);
    }

    private static void collectRetired(MinecraftServer s) {
        for (Horse horse : RETIRED) if (!horse.isRemoved()) horse.ejectPassengers();
        RETIRED.removeIf(horse -> horse.isRemoved() || !horse.isAlive()
            || returnEquipment(horse, horse.getOwnerUUID() == null ? null : s.getPlayerList().getPlayer(horse.getOwnerUUID())));
    }

    private static void publish(ServerPlayer player, Horse horse) {
        if (ServerPlayNetworking.canSend(player, MountPayload.TYPE))
            ServerPlayNetworking.send(player, new MountPayload(player.getUUID(), horse == null ? null : horse.getUUID()));
    }

    /** If the inventory is full or owner absent, preserve the actual horse and equipment for later collection. */
    private static boolean returnEquipment(Horse horse, ServerPlayer player) {
        // A dead player's inventory need not survive respawn when keepInventory was changed.
        // Retain the real equipment until the replacement live owner is present.
        if (player == null || !player.isAlive() || player.isRemoved()
                || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return false;
        SlotAccess saddle = horse.getSlot(AbstractHorse.EQUIPMENT_SLOT_OFFSET + AbstractHorse.INV_SLOT_SADDLE);
        List<ItemStack> equipment = new ArrayList<>();
        equipment.add(saddle.get());
        for (EquipmentSlot slot : EquipmentSlot.values()) equipment.add(horse.getItemBySlot(slot));
        // Only this horse's viewers are relevant. Let a carried stack settle normally before reclamation.
        List<ServerPlayer> viewers = player.getServer().getPlayerList().getPlayers().stream()
            .filter(p -> p.containerMenu instanceof net.minecraft.world.inventory.HorseInventoryMenu
                && p.containerMenu.slots.stream().anyMatch(slot -> !horse.hasInventoryChanged(slot.container))).toList();
        if (viewers.stream().anyMatch(p -> !p.containerMenu.getCarried().isEmpty())
                || !fits(player.getInventory().items, equipment)) return false;
        for (ServerPlayer viewer : viewers) viewer.closeContainer();
        List<ItemStack> returned = new ArrayList<>();
        ItemStack saddleStack = saddle.get(); saddle.set(ItemStack.EMPTY); returned.add(saddleStack);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = horse.getItemBySlot(slot); horse.setItemSlot(slot, ItemStack.EMPTY); returned.add(stack);
        }
        for (ItemStack stack : returned) if (!stack.isEmpty()) player.getInventory().add(stack);
        player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
        horse.discard();
        return true;
    }

    static boolean fits(List<ItemStack> inventory, List<ItemStack> incoming) {
        List<ItemStack> simulated = inventory.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (ItemStack stack : incoming) {
            int left = stack.getCount();
            for (ItemStack present : simulated) if (!present.isEmpty() && ItemStack.isSameItemSameComponents(present, stack)) {
                int moved = Math.min(left, Math.max(0, Math.min(64, present.getMaxStackSize()) - present.getCount()));
                present.grow(moved); left -= moved;
            }
            for (int i = 0; left > 0 && i < simulated.size(); i++) if (simulated.get(i).isEmpty()) {
                int moved = Math.min(left, Math.min(64, stack.getMaxStackSize()));
                simulated.set(i, stack.copyWithCount(moved)); left -= moved;
            }
            if (left > 0) return false;
        }
        return true;
    }
}
