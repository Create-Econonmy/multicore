package com.multicore.mixin.compat.rhino;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.multicore.core.ModLock;
import dev.latvian.mods.rhino.Callable;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * {@code Context.callSync} runs a script inside {@code synchronized (this.lock)}. With the mod lock taken inside it
 * (see {@link InterpretedFunctionMixin}), a thread that already held the mod lock (a locked block entity whose item
 * fires a script event) and one that did not took the two locks in opposite orders: the live server froze on
 * 2026-11-28.
 * <p>
 * A thread that takes the mod lock therefore runs the script under the mod lock alone, without Rhino's lock. Taking
 * the mod lock first and then Rhino's would not be enough: a cross-dimension wait inside a script lets go of the mod
 * lock ({@link ModLock#suspend}) but cannot let go of a Java monitor. Other threads keep Rhino's own locking.
 */
@Pseudo
@Mixin(targets = "dev.latvian.mods.rhino.Context", remap = false)
public abstract class ContextMixin {
    @WrapMethod(method = "callSync")
    private Object multicore$lockCallSync(Callable function, Scriptable scope, Scriptable thisObj, Object[] args,
            Operation<Object> original) {
        if (!ModLock.enterForMod("kubejs")) {
            return original.call(function, scope, thisObj, args);
        }
        try {
            return function.call((Context) (Object) this, scope, thisObj, args);
        } finally {
            ModLock.release();
        }
    }
}
