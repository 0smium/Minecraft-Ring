package dev.ermc.bridge.client.integrations;

import dev.ermc.bridge.TerrainManager;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/** Optional 3530 access, resolved once; scan workers only read the volatile gate. */
public final class MinimapScanAccess {
	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	private static final String MINIMAP = "net.ccbluex.liquidbounce.integration.theme.component.types.minimap.";
	private static volatile boolean suppressed;
	private static boolean attempted;
	private static MethodHandle enabled, clear, subscribers, subscribe, unsubscribe;
	private static Object subscriber;
	private static Object world;

	private MinimapScanAccess() { }

	public static boolean suppressed() { return suppressed; }

	public static void tick(Minecraft mc) {
		try {
			if (!attempted) {
				attempted = true;
				resolve();
			}
			if (enabled == null) return;
			boolean next = mc.level != null && TerrainManager.isBridgeWorld() && !(boolean) enabled.invokeExact();
			boolean changedWorld = world != mc.level;
			world = mc.level;
			if (next) {
				if (!suppressed || changedWorld) {
					suppressed = true;
					clear.invokeExact();
					LOG.info("LiquidBounce minimap background scan suspended: bridge component disabled");
				}
			} else if (suppressed) {
				// Keep ordinary LB subscription ownership. Re-subscribe only if HUD still owns
				// this subscriber; LB queues fresh scans of its already loaded chunks.
				boolean registered = ((List<?>) subscribers.invokeExact()).contains(subscriber);
				if (registered) unsubscribe.invokeExact(subscriber);
				else clear.invokeExact();
				suppressed = false;
				if (registered) subscribe.invokeExact(subscriber);
				LOG.info("LiquidBounce minimap background scan restored; cached map rebuilt");
			}
		} catch (Throwable failure) {
			// Unknown LB layout leaves its normal behavior intact, including Core-only runs.
			enabled = null;
			suppressed = false;
			LOG.warn("LiquidBounce minimap scan compatibility unavailable", failure);
		}
	}

	private static void resolve() throws ReflectiveOperationException {
		ClassLoader loader = MinimapScanAccess.class.getClassLoader();
		Class<?> componentType = Class.forName(MINIMAP + "MinimapComponent", true, loader);
		Object component = componentType.getField("INSTANCE").get(null);
		Class<?> subscriberType = Class.forName(MINIMAP + "ChunkRenderer$MinimapChunkUpdateSubscriber", true, loader);
		subscriber = subscriberType.getField("INSTANCE").get(null);
		Class<?> scannerType = Class.forName("net.ccbluex.liquidbounce.utils.block.ChunkScanner", true, loader);
		Object scanner = scannerType.getField("INSTANCE").get(null);
		Class<?> subscriberContract = Class.forName("net.ccbluex.liquidbounce.utils.block.ChunkScanner$BlockChangeSubscriber", false, loader);
		MethodHandles.Lookup lookup = MethodHandles.publicLookup();
		clear = lookup.unreflect(subscriberType.getMethod("clearAllChunks")).bindTo(subscriber);
		subscribers = lookup.unreflect(scannerType.getMethod("access$getSubscribers$p"))
			.asType(MethodType.methodType(List.class));
		subscribe = lookup.unreflect(scannerType.getMethod("subscribe", subscriberContract)).bindTo(scanner)
			.asType(MethodType.methodType(void.class, Object.class));
		unsubscribe = lookup.unreflect(scannerType.getMethod("unsubscribe", subscriberContract)).bindTo(scanner)
			.asType(MethodType.methodType(void.class, Object.class));
		// Resolve last so a partially unavailable layout never becomes an active gate.
		enabled = lookup.unreflect(componentType.getMethod("getEnabled$liquidbounce")).bindTo(component);
	}
}
