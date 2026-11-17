package com.multicore.universal.mixin.safety;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Vanilla crashes the server ("Accessed from multiple threads") when two threads use the same LegacyRandomSource.
 * Many mods keep one in a static field. With several dimension threads that becomes a random crash, so the
 * compare-and-set is retried instead, the same way java.util.Random does it.
 */
@Mixin(LegacyRandomSource.class)
public abstract class LegacyRandomSourceMixin {
    @Shadow
    @Final
    private AtomicLong seed;

    @Shadow
    @Final
    private MarsagliaPolarGaussian gaussianSource;

    /**
     * @author MultiCore
     * @reason Retry instead of throwing when another thread won the race.
     */
    @Overwrite
    public void setSeed(long seed) {
        this.seed.set((seed ^ 25214903917L) & 281474976710655L);
        this.gaussianSource.reset();
    }

    /**
     * @author MultiCore
     * @reason Retry instead of throwing when another thread won the race.
     */
    @Overwrite
    public int next(int size) {
        long current;
        long next;
        do {
            current = this.seed.get();
            next = current * 25214903917L + 11L & 281474976710655L;
        } while (!this.seed.compareAndSet(current, next));
        return (int) (next >> 48 - size);
    }
}
