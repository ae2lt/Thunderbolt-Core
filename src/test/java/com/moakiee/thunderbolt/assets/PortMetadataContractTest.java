package com.moakiee.thunderbolt.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class PortMetadataContractTest {
    private static final Path PROPERTIES_FILE = Path.of("gradle.properties");
    private static final Path GENERATED_REFMAP =
            Path.of("build", "tmp", "compileJava", "thunderbolt.refmap.json");

    @Test
    void requiredDependenciesMatchTheVerifiedForgeBaseline() throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(PROPERTIES_FILE)) {
            properties.load(reader);
        }

        // Baseline synced with AE2 15.4.10 (AE2 declares forge [47.1.3,)).
        assertEquals("[47.1.3,)", properties.getProperty("forge_version_range"));
        assertEquals("[15.4.10,16.0.0)", properties.getProperty("ae2_version_range"));
    }

    @Test
    void publishedArtifactsRetainTheProjectLicense() throws IOException {
        assertTrue(Files.size(Path.of("LICENSE")) > 0);

        String buildScript = Files.readString(Path.of("build.gradle"));
        assertTrue(buildScript.contains("from('LICENSE')"));
        assertTrue(buildScript.contains("into 'META-INF'"));
        assertTrue(buildScript.contains("tasks.named('jarJar', Jar)"));
    }

    @Test
    void productionRefmapIncludesInheritedMenuLifecycleMethods() throws IOException {
        String refmap = Files.readString(GENERATED_REFMAP);

        assertTrue(refmap.contains(
                "\"broadcastChanges()V\": "
                        + "\"Lappeng/menu/me/crafting/CraftingCPUMenu;m_38946_()V\""));
        assertTrue(refmap.contains(
                "\"removed(Lnet/minecraft/world/entity/player/Player;)V\": "
                        + "\"Lappeng/menu/me/crafting/CraftingCPUMenu;"
                        + "m_6877_(Lnet/minecraft/world/entity/player/Player;)V\""));
    }
}
