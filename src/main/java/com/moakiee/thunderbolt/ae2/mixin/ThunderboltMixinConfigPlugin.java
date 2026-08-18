package com.moakiee.thunderbolt.ae2.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

// Forge 1.20.1: ModList lives in fmlcore as net.minecraftforge.fml.ModList.
// (The neoform-era package net.minecraftforge.fml.loading.moddiscovery.ModList does not exist here.)
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;

/** Applies optional-addon mixins only when their owning mod is present. */
public final class ThunderboltMixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("thunderbolt");
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean apply = OptionalMixinSelector.shouldApply(
                mixinClassName, ThunderboltMixinConfigPlugin::isModLoaded);
        LOGGER.debug("Mixin select: {} -> {} : {}", mixinClassName, targetClassName, apply);
        return apply;
    }

    private static boolean isModLoaded(String modId) {
        // Mixin application runs before ModList.init() fills its mod-file index, so query the
        // early loading list first: it is populated right after the mods folder is scanned.
        try {
            var loading = LoadingModList.get();
            if (loading != null && loading.getModFileById(modId) != null) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // fall through to the full ModList below
        }
        try {
            var modList = ModList.get();
            return modList != null && modList.getModFileById(modId) != null;
        } catch (RuntimeException ignored) {
            // Neither list is usable (e.g. a non-standard loader invokes the plugin before mod
            // discovery). Prefer skipping optional mixins over force-applying them: that keeps
            // the game bootable when the target mod is absent.
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
