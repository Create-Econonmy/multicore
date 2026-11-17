package com.multicore.universal.coremod;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerActivity;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.tree.ClassNode;

/**
 * Weaves the mod lock into every class of every mod that is not built in (see {@link LockWeaver}). Runs before Mixin,
 * so other mods' mixins into these classes are merged into already-wrapped methods; the mixin extension in the main
 * mod then takes care of the merged code.
 */
final class ModClassTransformer implements ITransformer<ClassNode> {
    private final ModCatalog catalog;
    private final AtomicInteger classes = new AtomicInteger();
    private final AtomicLong methods = new AtomicLong();

    ModClassTransformer(ModCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public ClassNode transform(ClassNode node, ITransformerVotingContext context) {
        int index = this.catalog.modIndexOfTarget(node.name);
        if (index < 0 || LockWeaver.isExtensibleEnum(node)) {
            return node;
        }
        try {
            int wrapped = LockWeaver.weaveClass(node, index, this.catalog::isWovenModCode);
            ScopedStatics.rewriteFields(node, this.catalog.perThreadFields());
            this.methods.addAndGet(wrapped);
            int n = this.classes.incrementAndGet();
            if (n == 1 || n % 5000 == 0) {
                UniversalCoreMod.LOGGER.debug("[MultiCore Universal] {} mod classes woven ({} methods)", n, this.methods.get());
            }
        } catch (Throwable t) {
            UniversalCoreMod.LOGGER.error("[MultiCore Universal] Could not weave {}; its code runs without the mod lock", node.name, t);
        }
        return node;
    }

    /** Only real class loading: Mixin's and the frame computation's metadata reads see the original class. */
    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return ITransformerActivity.CLASSLOADING_REASON.equals(context.getReason()) ? TransformerVoteResult.YES : TransformerVoteResult.NO;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        Set<Target<ClassNode>> out = new HashSet<>();
        for (String name : this.catalog.targetNames()) {
            out.add(Target.targetClass(name.replace('/', '.')));
        }
        return out;
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[]{"multicore_universal"};
    }
}
