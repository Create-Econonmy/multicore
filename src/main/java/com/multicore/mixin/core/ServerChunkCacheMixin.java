package com.multicore.mixin.core;

import com.multicore.access.ServerChunkCacheAccessor;
import com.multicore.core.CrossDimension;
import com.multicore.core.CrossLevel;
import com.multicore.core.LevelThread;
import com.multicore.core.SerialQueue;
import com.multicore.core.TickThreads;
import javax.annotation.Nullable;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code mainThread} decides which thread may use this chunk cache directly. MultiCore points it at the dimension
 * thread for the duration of the parallel phase and back to the server thread afterwards, so vanilla code (and
 * Lithium's replacement of getChunk) keeps working unchanged on the owning thread.
 * <p>
 * Other dimension threads are routed to {@link CrossLevel} instead of vanilla's "block until the main thread runs it",
 * and their chunk ticket changes are deferred. Only the server's real levels are affected; mods' fake levels keep
 * vanilla behaviour.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements ServerChunkCacheAccessor {
    @Shadow
    @Final
    @Mutable
    Thread mainThread;

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Nullable
    protected abstract ChunkHolder getVisibleChunkIfPresent(long chunkPos);

    @Override
    public void multicore$setMainThread(Thread thread) {
        this.mainThread = thread;
    }

    @Override
    @Nullable
    public ChunkHolder multicore$getVisibleChunkIfPresent(long chunkPos) {
        return this.getVisibleChunkIfPresent(chunkPos);
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void multicore$foreignGetChunk(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> cir) {
        Thread current = Thread.currentThread();
        if (current != this.mainThread && current instanceof LevelThread && TickThreads.isRealLevel(this.level)) {
            cir.setReturnValue(CrossLevel.getChunk((ServerChunkCache) (Object) this, x, z, status, load));
        }
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void multicore$foreignGetChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        Thread current = Thread.currentThread();
        if (current != this.mainThread && current instanceof LevelThread && TickThreads.isRealLevel(this.level)) {
            cir.setReturnValue(CrossLevel.getChunkNow((ServerChunkCache) (Object) this, x, z));
        }
    }

    // ---- Chunk tickets are the owning thread's business; another dimension thread's changes wait for the serial phase ----

    @Inject(method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            at = @At("HEAD"), cancellable = true)
    private <T> void multicore$foreignAddTicket(TicketType<T> type, ChunkPos pos, int distance, T value, boolean forceTicks, CallbackInfo ci) {
        if (CrossDimension.mustDeferWriteTo(this.level)) {
            ServerChunkCache self = (ServerChunkCache) (Object) this;
            CrossLevel.noteAccess("adds a chunk ticket in another dimension (deferred to end of tick)", this.level);
            SerialQueue.defer("addRegionTicket", () -> self.addRegionTicket(type, pos, distance, value, forceTicks));
            ci.cancel();
        }
    }

    @Inject(method = "removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            at = @At("HEAD"), cancellable = true)
    private <T> void multicore$foreignRemoveTicket(TicketType<T> type, ChunkPos pos, int distance, T value, boolean forceTicks, CallbackInfo ci) {
        if (CrossDimension.mustDeferWriteTo(this.level)) {
            ServerChunkCache self = (ServerChunkCache) (Object) this;
            SerialQueue.defer("removeRegionTicket", () -> self.removeRegionTicket(type, pos, distance, value, forceTicks));
            ci.cancel();
        }
    }

    @Inject(method = "updateChunkForced", at = @At("HEAD"), cancellable = true)
    private void multicore$foreignForceChunk(ChunkPos pos, boolean add, CallbackInfo ci) {
        if (CrossDimension.mustDeferWriteTo(this.level)) {
            ServerChunkCache self = (ServerChunkCache) (Object) this;
            SerialQueue.defer("updateChunkForced", () -> self.updateChunkForced(pos, add));
            ci.cancel();
        }
    }
}
