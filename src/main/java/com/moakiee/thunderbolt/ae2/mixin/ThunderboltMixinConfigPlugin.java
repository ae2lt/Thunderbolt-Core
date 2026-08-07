package com.moakiee.thunderbolt.ae2.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

// Forge 1.20.1: ModList lives in fmlcore as net.minecraftforge.fml.ModList.
// (The neoform-era package net.minecraftforge.fml.loading.moddiscovery.ModList does not exist here.)
import net.minecraftforge.fml.ModList;

/** Applies optional-addon mixins only when their owning mod is present. */
public final class ThunderboltMixinConfigPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return OptionalMixinSelector.shouldApply(mixinClassName, ThunderboltMixinConfigPlugin::isModLoaded);
    }

    private static boolean isModLoaded(String modId) {
        try {
            var modList = ModList.get();
            return modList != null && modList.getModFileById(modId) != null;
        } catch (RuntimeException ignored) {
            // ModList 不可用时（例如非标准加载器在模组列表就绪前调用了本插件），
            // 宁可跳过可选 mixin，也不要强制应用：否则在目标模组未安装时会直接崩溃。
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
