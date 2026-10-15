package dev.ermc.bridge.client;

import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.HostInteractionSample;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Read-only menu observation drives the existing overlay switch, only for our action request. */
public final class HostInteractionClient {
    private static final Logger LOG = LoggerFactory.getLogger("erbridge");
    private static final InteractionHandoffPolicy POLICY = new InteractionHandoffPolicy();
    private HostInteractionClient() {}
    private static long now() { return System.nanoTime() / 1_000_000L; }
    private static InteractionHandoffPolicy.Identity identity(HostInteractionSample s) {
        return new InteractionHandoffPolicy.Identity(s.hostPid(), s.hostStartMs(), s.hostGeneration(), s.hostLife(), s.zone());
    }
    public static void requested(int sequence, HostInteractionSample beforeAction) {
        cancel();
        if (Platform.get() != Platform.WINDOWS || Overlay.hostMode()) return;
        if (beforeAction != null && beforeAction.ready() && !beforeAction.menuBlocking() && !beforeAction.resting())
            POLICY.request(sequence, identity(beforeAction), now());
    }
    public static void acknowledged(int sequence, int result) { POLICY.acknowledge(sequence, result); }
    public static void cancel() { POLICY.cancel(); }

    /** Runs even while Minecraft's integrated server is paused in host mode. */
    public static void onFrame(Minecraft mc, ErLink link, boolean hostUsable) {
        if (!POLICY.pending()) return;
        if (!hostUsable || mc.player == null || mc.level == null || Platform.get() != Platform.WINDOWS) { cancel(); return; }
        var sample = link.readHostInteraction();
        if (sample.isEmpty()) { cancel(); return; }
        HostInteractionSample s = sample.get();
        var focus = WindowsOverlay.foreground(mc.getWindow().getWindow(), link.hostProcessId());
        var decision = POLICY.observe(identity(s), s.ready(), s.menuBlocking(), s.canResume(), Overlay.hostMode(),
            focus == WindowsOverlay.Foreground.OTHER,
            focus == WindowsOverlay.Foreground.GAME || focus == WindowsOverlay.Foreground.MINECRAFT, s.frame(), now());
        if (decision == InteractionHandoffPolicy.Decision.TO_HOST) {
            LOG.info("Interaction: native menu opened after MC action; handing control to Elden Ring (rest flags {})", s.restFlags());
            Overlay.switchToHost(mc);
        } else if (decision == InteractionHandoffPolicy.Decision.TO_MC) {
            LOG.info("Interaction: native menu closed and controls ready; returning to Minecraft");
            Overlay.switchToMc(mc);
        }
    }
}
