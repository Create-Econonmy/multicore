package com.multicore.mixin.core;

import com.multicore.core.ContentPolicy;
import com.multicore.core.LockSource;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Caches the thread-safety decision for this Block. See {@link ContentPolicy}. */
@Mixin(Block.class)
public abstract class BlockMixin implements ContentPolicy.PolicyHolder {
    @Unique
    private volatile int multicore$policyStamp;
    @Unique
    @Nullable
    private volatile LockSource multicore$lockSource;

    @Override
    public int multicore$policyStamp() {
        return this.multicore$policyStamp;
    }

    @Override
    public void multicore$setPolicyStamp(int stamp) {
        this.multicore$policyStamp = stamp;
    }

    @Override
    @Nullable
    public LockSource multicore$lockSource() {
        return this.multicore$lockSource;
    }

    @Override
    public void multicore$setLockSource(@Nullable LockSource source) {
        this.multicore$lockSource = source;
    }
}
