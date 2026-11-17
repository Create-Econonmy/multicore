package com.multicore.universal.access;

import javax.annotation.Nullable;
import net.minecraft.server.level.ChunkHolder;

/** Implemented by {@code ServerChunkCacheMixin}. */
public interface ServerChunkCacheAccessor {
    void mcu$setMainThread(Thread thread);

    @Nullable
    ChunkHolder mcu$getVisibleChunkIfPresent(long chunkPos);
}
