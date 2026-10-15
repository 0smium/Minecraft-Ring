package dev.ermc.bridge.mixin;

import dev.ermc.bridge.collision.PlacedBlockCollisionSnapshots;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Successful placement/removal/state changes invalidate only the active experimental collision cache. */
@Mixin(LevelChunk.class)
public abstract class PlacedBlockColliderRevisionMixin {
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void erbridge$placedColliderChanged(BlockPos pos,BlockState after,boolean moved,CallbackInfoReturnable<BlockState> result){
        var level=((LevelChunk)(Object)this).getLevel();
        if(level instanceof ServerLevel server&&server.getServer().isSameThread())
            PlacedBlockCollisionSnapshots.INSTANCE.blockChanged(server,pos,result.getReturnValue(),after);
    }
}
