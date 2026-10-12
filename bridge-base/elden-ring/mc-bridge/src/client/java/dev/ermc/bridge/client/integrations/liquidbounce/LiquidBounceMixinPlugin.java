package dev.ermc.bridge.client.integrations.liquidbounce;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Optional compatibility for the pinned LiquidBounce release; Core has no LiquidBounce dependency. */
public final class LiquidBounceMixinPlugin implements IMixinConfigPlugin {
	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	private boolean supported;

	@Override
	public void onLoad(String mixinPackage) {
		String version = FabricLoader.getInstance().getModContainer("liquidbounce")
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent");
		supported = "0.22.0".equals(version);
		LOG.info("LiquidBounce target compatibility {} (version {}; supported release 0.22.0)",
			supported ? "enabled" : "skipped: unsupported or Core-only", version);
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return supported;
	}

	@Override
	public String getRefMapperConfig() { return null; }

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }

	@Override
	public List<String> getMixins() { return null; }

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
