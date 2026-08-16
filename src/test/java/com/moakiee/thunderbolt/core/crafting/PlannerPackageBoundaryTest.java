package com.moakiee.thunderbolt.core.crafting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PlannerPackageBoundaryTest {
    @Test
    void supportPackageDoesNotDependOnPlannerImplementation() throws Exception {
        var support = Path.of("src/main/java/com/moakiee/thunderbolt/core/crafting/support");
        try (var files = Files.walk(support)) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var source = Files.readString(file);
                assertFalse(
                        source.contains("com.moakiee.thunderbolt.core.crafting.planner"),
                        () -> "support must not import planner implementation: " + file);
            }
        }
    }

    @Test
    void apiAndCoreDoNotDependOnImplementationMixins() throws Exception {
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/api"),
                "com.moakiee.thunderbolt.core",
                "API must not import core implementation");
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/api"),
                "com.moakiee.thunderbolt.mixin",
                "API must not import mixin implementation");
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/core"),
                "com.moakiee.thunderbolt.mixin",
                "core must not import mixin implementation");
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/ae2"),
                "com.moakiee.thunderbolt.mixin",
                "AE2 adapters outside the mixin layer must not import mixin implementation");
        assertSourcesDoNotContain(
                Path.of("src/main/java/com/moakiee/thunderbolt/compat"),
                "com.moakiee.thunderbolt.mixin",
                "compat adapters outside the mixin layer must not import mixin implementation");
    }

    @Test
    void craftingCalculationUsesOneIsolationAndSessionCleanupPath() throws Exception {
        var source = Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/mixin/ae2/crafting/"
                        + "CraftingCalculationMixin.java"));

        assertTrue(source.contains("? thunderbolt$runVanillaCandidate"));
        assertTrue(source.contains("try (session)"));
        assertFalse(source.contains("PlanningAttemptMonitor.start("));
    }

    @Test
    void mixinBridgeLivesOutsideTheDeclaredMixinPackage() {
        var source = Path.of("src/main/java/com/moakiee/thunderbolt");
        assertTrue(Files.exists(source.resolve("ae2/crafting/CraftingPlanningControl.java")));
        assertTrue(Files.exists(source.resolve("ae2/crafting/CapturedPlanningChoice.java")));
        assertFalse(Files.exists(source.resolve("mixin/ae2/crafting/CraftingPlanningControl.java")));
        assertFalse(Files.exists(source.resolve("mixin/ae2/crafting/CapturedPlanningChoice.java")));
    }

    @Test
    void declaredMixinPackageContainsOnlyMixinsAndConfigInfrastructure() throws Exception {
        var mixin = Path.of("src/main/java/com/moakiee/thunderbolt/mixin");
        try (var files = Files.walk(mixin)) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var name = file.getFileName().toString();
                if (name.equals("OptionalMixinSelector.java")
                        || name.equals("ThunderboltMixinConfigPlugin.java")) {
                    continue;
                }
                var source = Files.readString(file);
                assertTrue(source.contains("@Mixin"),
                        () -> "ordinary runtime helper must live outside the declared Mixin package: " + file);
            }
        }
    }

    @Test
    void v2EngineLivesWithThePlannerItAdapts() {
        var crafting = Path.of("src/main/java/com/moakiee/thunderbolt/core/crafting");
        assertTrue(Files.exists(crafting.resolve("planner/ThunderboltV2PlanningEngine.java")));
        assertFalse(Files.exists(crafting.resolve("algorithm/ThunderboltV2PlanningEngine.java")));
    }

    private static void assertSourcesDoNotContain(
            Path root, String forbidden, String message) throws Exception {
        try (var files = Files.walk(root)) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var source = Files.readString(file);
                assertFalse(source.contains(forbidden), () -> message + ": " + file);
            }
        }
    }
}
