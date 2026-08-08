package com.moakiee.thunderbolt.ae2.overload.pattern;

import net.minecraft.world.item.ItemStack;

@FunctionalInterface
public interface PlainPatternResolver {
   ParsedPatternDefinition resolve(ItemStack var1);
}
