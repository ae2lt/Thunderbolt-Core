package com.moakiee.thunderbolt.ae2.overload.pattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class Ae2PlainPatternResolver implements PlainPatternResolver {
   private final Level level;

   public Ae2PlainPatternResolver(Level level) {
      this.level = level;
   }

   @Override
   public ParsedPatternDefinition resolve(ItemStack sourcePatternStack) {
      IPatternDetails sourceDetails = PatternDetailsHelper.decodePattern(sourcePatternStack, this.level);
      if (sourceDetails == null) {
         throw new IllegalArgumentException("could not decode source pattern stack: " + sourcePatternStack);
      } else {
         return OverloadPatternSupport.toParsedDefinition(sourcePatternStack, sourceDetails, this.level.registryAccess());
      }
   }
}
