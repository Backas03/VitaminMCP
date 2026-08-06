package moe.vitamin.minecraft.mcp.bot.spi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The SPI names no protocol library type. */
class BotBackendContractTest {

    /** Where a type in an SPI signature is allowed to come from. */
    private static final List<String> ALLOWED = List.of("java.", "moe.vitamin.minecraft.mcp.");

    @Test
    void everySignatureIsLibraryFree() {
        List<String> offenders = new ArrayList<>();

        for (Method method : BotBackend.class.getMethods()) {
            check(method.getReturnType(), method, offenders);
            for (Class<?> parameter : method.getParameterTypes()) {
                check(parameter, method, offenders);
            }
            for (Class<?> thrown : method.getExceptionTypes()) {
                check(thrown, method, offenders);
            }
        }

        assertTrue(offenders.isEmpty(),
                "BotBackend must be expressible without a protocol library, but:\n  "
                        + String.join("\n  ", offenders));
    }

    private static void check(Class<?> type, Method method, List<String> offenders) {
        Class<?> element = type;
        while (element.isArray()) {
            element = element.getComponentType();
        }
        if (element.isPrimitive()) {
            return;
        }
        String name = element.getName();
        if (ALLOWED.stream().noneMatch(name::startsWith)) {
            offenders.add(method.getName() + " uses " + name);
        }
    }
}
