package com.multicore.mixin.compat.createew;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create Electronic Warfare 1.0.1 looks through the fields of every ticking block entity's class (and its
 * superclasses) for energy storage. getDeclaredFields loads every field's type, and some block entities have fields
 * typed with ComputerCraft classes (optional compat in Create Diesel Generators, Bits 'n' Bobs, Propulsion...).
 * Without CC: Tweaked installed that throws NoClassDefFoundError, which EW does not catch (it catches only
 * RuntimeException), so the server crashed (found 2026-11-26 with the live server's mods, with or without MultiCore).
 * A class whose fields cannot load has no energy field EW can use: treat it as having none and keep going up.
 */
@Pseudo
@Mixin(targets = "com.mrvew.createew.compat.EnergyCompat", remap = false)
public abstract class EnergyCompatFieldsMixin {
    @WrapOperation(method = "lambda$fields$0", at = @At(value = "INVOKE", target = "Ljava/lang/Class;getDeclaredFields()[Ljava/lang/reflect/Field;"), remap = false, require = 0)
    private static Field[] multicore$skipUnloadableFields(Class<?> type, Operation<Field[]> original) {
        try {
            return original.call(type);
        } catch (LinkageError e) {
            return new Field[0];
        }
    }
}
