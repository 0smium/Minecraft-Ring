package dev.ermc.bridge.client.integrations;

import dev.ermc.bridge.api.CapabilitySnapshot;

/** The network adapter publishes the server's immutable view; absence in a challenge means locked. */
public final class CapabilityPolicyClient {
    public record View(boolean challengeActive, CapabilitySnapshot snapshot, long generation) {}
    private static volatile View current = new View(false, null, 0);

    private CapabilityPolicyClient() {}

    public static View view() { return current; }

    /** The caller authenticates/binds the packet and applies it on the client thread. */
    public static synchronized void publish(boolean challengeActive, CapabilitySnapshot snapshot) {
        View old = current;
        if (old.challengeActive() == challengeActive && java.util.Objects.equals(old.snapshot(), challengeActive ? snapshot : null)) return;
        if (challengeActive && snapshot != null && old.snapshot() != null
                && old.snapshot().binding().equals(snapshot.binding())
                && snapshot.policyRevision() < old.snapshot().policyRevision()) return;
        if (old.challengeActive() != challengeActive || old.challengeActive() && (snapshot == null
                || old.snapshot() != null && !old.snapshot().binding().equals(snapshot.binding()))) {
            LiquidBounceCapabilityAccess.stopManagedModules();
        }
        current = new View(challengeActive, challengeActive ? snapshot : null, old.generation() + 1);
    }

    /** Disconnect calls this before destroying the player/world so LB's normal disable can clean up. */
    public static void clear() { publish(false, null); }
}
