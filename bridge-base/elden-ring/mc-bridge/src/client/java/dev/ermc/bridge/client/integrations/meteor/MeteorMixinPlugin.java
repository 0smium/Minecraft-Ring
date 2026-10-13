package dev.ermc.bridge.client.integrations.meteor;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Optional fixed-version candidate; Core never links Meteor classes. */
public final class MeteorMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOG = LoggerFactory.getLogger("erbridge");
    private boolean supported;

    @Override public void onLoad(String mixinPackage) {
        String version = FabricLoader.getInstance().getModContainer("meteor-client")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent");
        supported = "0.5.8".equals(version);
        LOG.info("Meteor compatibility candidate {} (version {}; pinned API 0.5.8)",
                supported ? "enabled" : "skipped: absent or unsupported", version);
    }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return supported; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
}
