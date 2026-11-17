package com.multicore.access;

import javax.annotation.Nullable;
import net.minecraft.server.level.ChunkHolder;

/** Implemented by {@code ServerChunkCacheMixin}. */
public interface ServerChunkCacheAccessor {
    void multicore$setMainThread(Thread thread);

    @Nullable
    ChunkHolder multicore$getVisibleChunkIfPresent(long chunkPos);
}
