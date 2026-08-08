package com.moakiee.thunderbolt.ae2.mixin;

import java.util.List;
import java.util.Set;
import net.minecraftforge.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class ThunderboltMixinConfigPlugin implements IMixinConfigPlugin {
   public void onLoad(String mixinPackage) {
   }

   public String getRefMapperConfig() {
      return null;
   }

   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      return OptionalMixinSelector.shouldApply(mixinClassName, ThunderboltMixinConfigPlugin::isModLoaded);
   }

   private static boolean isModLoaded(String modId) {
      try {
         ModList loadingMods = ModList.get();
         return loadingMods != null && loadingMods.isLoaded(modId);
      } catch (RuntimeException var2) {
         return true;
      }
   }

   public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
   }

   public List<String> getMixins() {
      return null;
   }

   public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }

   public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }
}
