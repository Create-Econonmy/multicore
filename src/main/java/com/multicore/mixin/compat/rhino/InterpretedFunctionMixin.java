package com.multicore.mixin.compat.rhino;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.core.ModLock;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * KubeJS scripts run in Rhino with one shared, non-thread-safe Context and shared script globals. KubeJS calls
 * scripts from many places besides events (random ticks, custom item and block callbacks, falling blocks), so the
 * lock is taken where every script function is entered. Nested script calls re-enter the lock cheaply.
 */
@Pseudo
@Mixin(targets = "dev.latvian.mods.rhino.InterpretedFunction", remap = false)
public abstract class InterpretedFunctionMixin {
    @WrapMethod(method = "call")
    private Object multicore$lockCall(Context cx, Scriptable scope, Scriptable thisObj, Object[] args, Operation<Object> original) {
        boolean held = ModLock.enterForMod("kubejs");
        try {
            return original.call(cx, scope, thisObj, args);
        } finally {
            ModLock.exit(held);
        }
    }

    @WrapMethod(method = "exec")
    private Object multicore$lockExec(Context cx, Scriptable scope, Operation<Object> original) {
        boolean held = ModLock.enterForMod("kubejs");
        try {
            return original.call(cx, scope);
        } finally {
            ModLock.exit(held);
        }
    }
}
