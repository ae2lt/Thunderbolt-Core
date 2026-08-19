package com.moakiee.thunderbolt.core.eject;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.core.eject.ThunderboltGhostOutputBlockEntity;

public final class ThunderboltBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ThunderboltCore.MODID);

    public static final RegistryObject<BlockEntityType<ThunderboltGhostOutputBlockEntity>>
            GHOST_OUTPUT = TYPES.register(
                    "ghost_output",
                    () -> BlockEntityType.Builder.of(
                            ThunderboltGhostOutputBlockEntity::new, Blocks.AIR).build(null));

    private ThunderboltBlockEntities() {}
}
