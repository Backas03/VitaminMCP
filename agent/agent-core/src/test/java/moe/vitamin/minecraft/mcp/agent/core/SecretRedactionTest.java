package moe.vitamin.minecraft.mcp.agent.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The pattern that decides whether a plugin's config value reaches an LLM's context.
 *
 * <p>Reading a plugin's config is worth having — it is how "works for admins only" gets answered
 * without asking a human to paste a file. It is also where database passwords live. This is the
 * one line between those, so it is tested directly rather than through a running server.
 *
 * <p>Erring wide is deliberate. A false positive costs one question to a human; a false negative
 * cannot be taken back.
 */
class SecretRedactionTest {

    private static final Pattern SECRET = secretPattern();

    @Test
    void redactsTheKeysAPluginKeepsCredentialsUnder() {
        for (String key : new String[] {
                "password", "pass", "passwd", "db.password", "mysql.pass",
                "secret", "client-secret", "token", "auth-token", "authToken",
                "apiKey", "api_key", "api-key", "credentials.user", "credential",
                "database.dsn", "discord.webhook", "licenseKey", "licence"}) {
            assertTrue(SECRET.matcher(key).find(), key + " should have been redacted");
        }
    }

    @Test
    void leavesOrdinarySettingsAlone() {
        for (String key : new String[] {
                "scenario", "kit.enabled", "teams.Tester1", "prefix", "cooldownSeconds",
                "messages.welcome", "world", "radius", "debug", "language"}) {
            assertFalse(SECRET.matcher(key).find(), key + " should have been shown");
        }
    }

    /** The production pattern itself, so this cannot pass against a copy that has drifted. */
    private static Pattern secretPattern() {
        try {
            Field field = CaptureService.class.getDeclaredField("SECRET_KEY");
            field.setAccessible(true);
            return (Pattern) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("CaptureService.SECRET_KEY moved; this test guards it", e);
        }
    }
}
