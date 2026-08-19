package com.moakiee.thunderbolt.test;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.fml.loading.EarlyLoadingException;
import net.minecraftforge.fml.loading.LoadingModList;

/** Initializes the Forge 1.20.1 registries once before a plain JUnit test touches Level or Items. */
public final class MinecraftTestBootstrap {
    private static boolean initialized;

    private MinecraftTestBootstrap() {
    }

    public static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        LoadingModList.of(List.of(), List.of(),
                new EarlyLoadingException("test bootstrap", null, List.of()));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        initialized = true;
    }
}
