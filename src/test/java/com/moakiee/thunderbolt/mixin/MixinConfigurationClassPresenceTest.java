package com.moakiee.thunderbolt.mixin;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class MixinConfigurationClassPresenceTest {
    @Test
    void everyConfiguredMixinHasACompiledClass() throws IOException {
        var classLoader = MixinConfigurationClassPresenceTest.class.getClassLoader();
        try (var stream = classLoader.getResourceAsStream("thunderbolt.mixins.json")) {
            assertNotNull(stream, "thunderbolt.mixins.json must be present on the test runtime classpath");
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var basePackage = config.get("package").getAsString();
            assertConfiguredClassesPresent(classLoader, basePackage, config, "mixins");
            assertConfiguredClassesPresent(classLoader, basePackage, config, "client");
        }
    }

    private static void assertConfiguredClassesPresent(
            ClassLoader classLoader,
            String basePackage,
            JsonObject config,
            String section) {
        JsonArray mixins = config.getAsJsonArray(section);
        if (mixins == null) {
            return;
        }
        for (var entry : mixins) {
            var className = basePackage + "." + entry.getAsString();
            var classResource = className.replace('.', '/') + ".class";
            assertNotNull(
                    classLoader.getResource(classResource),
                    () -> "configured mixin class is missing: " + className);
        }
    }
}
