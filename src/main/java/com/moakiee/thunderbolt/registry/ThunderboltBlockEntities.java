package com.moakiee.thunderbolt.registry;

import com.moakiee.thunderbolt.internal.eject.ThunderboltGhostOutputBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityType.Builder;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

public final class ThunderboltBlockEntities {
   public static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "thunderbolt");
   public static final RegistryObject<BlockEntityType<ThunderboltGhostOutputBlockEntity>> GHOST_OUTPUT = TYPES.register(
      "ghost_output", () -> Builder.of(ThunderboltGhostOutputBlockEntity::new, new Block[]{Blocks.AIR}).build(null)
   );

   private ThunderboltBlockEntities() {
   }
}
