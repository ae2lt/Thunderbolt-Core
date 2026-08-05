package com.moakiee.thunderbolt.api.crafting.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

class BatchCraftingProviderContractTest {

    @Test
    void legacyThirdPartySingleSeedCapabilityFeedsTheNeutralSharedInputName() {
        var legacyProvider = proxy((proxy, method, args) -> {
            if (method.getName().equals("supportsSingleSeedBatch")) {
                return true;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return defaultValue(method.getReturnType());
        });

        assertTrue(legacyProvider.supportsSingleSeedBatch());
        assertTrue(legacyProvider.supportsSharedBatchInputs());
    }

    @Test
    void providerWithoutAnOptInKeepsOrdinaryInputSemantics() {
        var ordinaryProvider = proxy((proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return defaultValue(method.getReturnType());
        });

        assertFalse(ordinaryProvider.supportsSharedBatchInputs());
    }

    private static IBatchCraftingProvider proxy(InvocationHandler handler) {
        return (IBatchCraftingProvider) Proxy.newProxyInstance(
                BatchCraftingProviderContractTest.class.getClassLoader(),
                new Class<?>[]{IBatchCraftingProvider.class},
                handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
