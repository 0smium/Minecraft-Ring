package dev.ermc.bridge.client;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog;
import dev.ermc.bridge.api.CharacterBinding;
import dev.ermc.bridge.client.integrations.CapabilityPolicyClient;
import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import dev.ermc.bridge.gameplay.runtime.B01Payload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Authenticated S2C policy + a small status/limit display, independent of LB's fixed slider ranges. */
public final class ProgressionClient {
    private static B01Payload latest;
    private static long receivedAt, revision;
    private static CharacterBinding binding;
    private static boolean awaiting;
    private ProgressionClient() {}

    public static void initialize() {
        ClientPlayConnectionEvents.INIT.register((handler, mc) -> {
            reset();
            if (mc.getSingleplayerServer() != null && TerrainManager.isBridgeWorld()) {
                awaiting = true; CapabilityPolicyClient.publish(true, null);
            }
        });
        BridgeLifecycle.CLIENT_DISCONNECTED.register(ProgressionClient::reset);
        ClientPlayNetworking.registerGlobalReceiver(B01Payload.TYPE, (payload, context) -> {
            // Fabric dispatches this handler on the render thread; it never reads server statics.
            if (context.player() == null || !context.player().getUUID().equals(payload.owner())) return;
            if (payload.snapshot() != null) {
                if (binding != null && binding.equals(payload.snapshot().binding()) && payload.snapshot().policyRevision() < revision) return;
                binding = payload.snapshot().binding(); revision = payload.snapshot().policyRevision();
            }
            latest = payload; receivedAt = System.nanoTime(); awaiting = false;
            CapabilityPolicyClient.publish(payload.challenge(), payload.snapshot());
        });
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (latest != null && latest.challenge() && System.nanoTime() - receivedAt > 3_000_000_000L
                    && !mc.isPaused() && !awaiting) {
                awaiting = true; CapabilityPolicyClient.publish(true, null);
            }
            LiquidBounceCapabilityAccess.tick(mc);
        });
        HudRenderCallback.EVENT.register((graphics, delta) -> render(graphics));
    }
    private static void reset() {
        CapabilityPolicyClient.clear(); latest = null; binding = null; receivedAt = 0; revision = 0; awaiting = false;
    }
    private static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || Overlay.hostBusy()
                || !Overlay.active() || latest == null || !latest.challenge()) return;
        int y = g.guiHeight() - 64;
        g.drawString(mc.font, awaiting ? "成长：等待服务端策略，行为模块已锁定" : latest.status(), 6, y, 0xFFE8D8A0, true);
        if (!awaiting && latest.snapshot() != null) {
            long learned = latest.snapshot().abilities().values().stream().filter(grant -> grant.unlocked()).count();
            String line = "已学习 " + learned + "/" + AbilityCatalog.instance().entries().size()
                    + " 项能力 | K 查看分类、进阶与点数 | LB 参数已按等级约束";
            g.drawString(mc.font, line, 6, y + 11, 0xFFFFFFFF, true);
        }
    }
}
