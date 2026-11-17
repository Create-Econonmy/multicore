package com.multicore.testmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** A mod written as if there were only one server thread. Used by MultiCore Universal's GameTests. */
@Mod(TestMod.MOD_ID)
public final class TestMod {
    public static final String MOD_ID = "mcu_testmod";
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredBlock<RacerBlock> RACER = BLOCKS.register("racer", () -> new RacerBlock(BlockBehaviour.Properties.of()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RacerBlockEntity>> RACER_BE = BLOCK_ENTITIES.register("racer",
            () -> BlockEntityType.Builder.of(RacerBlockEntity::new, RACER.get()).build(null));

    public TestMod(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        NeoForge.EVENT_BUS.addListener(EntityTickEvent.Post.class, e -> {
            if (!e.getEntity().level().isClientSide()) {
                Shared.onEntityTick(e.getEntity());
            }
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.RegisterCommandsEvent.class, e -> e.getDispatcher().register(
                net.minecraft.commands.Commands.literal("mcutest").requires(s -> s.hasPermission(3))
                        .then(net.minecraft.commands.Commands.literal("start").executes(ctx -> {
                            Shared.reset();
                            Shared.recording = true;
                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("mcutest: recording"), false);
                            return 1;
                        }))
                        .then(net.minecraft.commands.Commands.literal("report").executes(ctx -> {
                            for (String line : Shared.report(ctx.getSource().getServer())) {
                                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(line), false);
                            }
                            return 1;
                        }))));
    }

    public static final class RacerBlock extends Block implements EntityBlock {
        public RacerBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new RacerBlockEntity(pos, state);
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            if (level.isClientSide() || type != RACER_BE.get()) {
                return null;
            }
            return (l, pos, s, be) -> Shared.racerTick((ServerLevel) l, pos);
        }
    }

    public static final class RacerBlockEntity extends BlockEntity {
        public RacerBlockEntity(BlockPos pos, BlockState state) {
            super(RACER_BE.get(), pos, state);
        }
    }
}
