package moe.vitamin.minecraft.mcp.bot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Pool bookkeeping that needs no server. */
class BotPoolTest {

    @Test
    void rejectsANonsenseLimit() {
        assertThrows(IllegalArgumentException.class, () -> new BotPool("127.0.0.1", 25565, 0));
    }

    @Test
    void startsEmptyAndRemembersItsLimit() {
        try (BotPool pool = new BotPool("127.0.0.1", 25565, 3)) {
            assertEquals(0, pool.size());
            assertEquals(3, pool.maxBots());
        }
    }

    @Test
    void despawningAnAbsentBotIsHarmless() {
        try (BotPool pool = new BotPool("127.0.0.1", 25565)) {
            pool.despawn("NeverExisted");
            assertEquals(0, pool.size());
        }
    }

    @Test
    void closingTwiceIsHarmless() {
        BotPool pool = new BotPool("127.0.0.1", 25565);
        pool.close();
        pool.close();
        assertEquals(0, pool.size());
    }

    // The cap and duplicate-name rules are exercised in BotConnectionLiveTest, where bots
    // actually connect. Asserting them here would mean asserting that a connection to a dead
    // port fails, which says nothing about either rule.
}
