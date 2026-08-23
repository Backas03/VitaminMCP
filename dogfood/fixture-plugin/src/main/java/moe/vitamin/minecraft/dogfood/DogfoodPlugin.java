package moe.vitamin.minecraft.dogfood;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A small server plugin with one thing wrong with it, chosen by config.
 *
 * <p>It exists to be debugged. The dogfooding harness (dogfood/README.md) points a fresh agent at
 * a running copy of this, tells it what a player reported, and watches where VitaminMCP fails to
 * answer the question that comes next. That friction is the output; the bug is only bait.
 *
 * <p>So this is written the way an ordinary plugin is written, not the way this repository writes
 * code. The comments are a plugin author's comments, including where they are wrong. Nothing here
 * marks which line is the fault — {@code dogfood/ANSWERS.md} does, and a blind run must not read
 * it.
 */
public final class DogfoodPlugin extends JavaPlugin implements Listener {

    private Scenario scenario = Scenario.NONE;

    /** Players whose profile is still loading. Interactions from them are not theirs yet. */
    private final Set<UUID> loading = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scenario = Scenario.parse(getConfig().getString("scenario"));

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DogfoodFixture enabled, running scenario " + scenario.key() + ".");

        if (scenario == Scenario.DISABLED_FEATURE) {
            getLogger().info("kit.enabled is false in config.yml; /kit will not hand anything out.");
        }
        if (scenario == Scenario.DECORATED_CONFIG) {
            getLogger().info("shop.enabled is false in config.yml; /shop will not open.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This one is for players.");
            return true;
        }

        return switch (command.getName()) {
            case "kit" -> kit(player);
            case "shop" -> shop(player);
            case "top" -> top(player);
            default -> false;
        };
    }

    // ---------------------------------------------------------------- commands

    private boolean kit(Player player) {
        if (!getConfig().getBoolean("kit.enabled", true)) {
            return true;
        }
        giveKit(player);
        player.sendMessage(Component.text("Here is your kit."));
        return true;
    }

    private boolean shop(Player player) {
        if (scenario == Scenario.SILENT_REFUSAL && !player.hasPermission("dogfood.shop")) {
            // Chat is noisy, and players complain about walls of red text. The action bar is
            // tidier and they see it right above the hotbar.
            player.sendActionBar(Component.text("You cannot use the shop yet."));
            return true;
        }

        Inventory shop = getServer().createInventory(player, 27, Component.text("Shop"));
        shop.setItem(11, new ItemStack(Material.DIAMOND, 1));
        shop.setItem(15, new ItemStack(Material.EMERALD, 1));
        player.openInventory(shop);
        return true;
    }

    private boolean top(Player player) {
        if (scenario != Scenario.ASYNC_REPLY) {
            player.sendMessage(Component.text("1. " + player.getName() + " — 10 points"));
            return true;
        }

        // The leaderboard is a database query, so it must not run on the main thread.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            sleep(600);
            player.sendMessage(Component.text("1. " + player.getName() + " — 10 points"));
        });
        return true;
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (scenario == Scenario.JOIN_LOCKOUT) {
            loading.add(player.getUniqueId());
            // Profiles come from the database, so the player is not ready the instant they join.
            getServer().getScheduler().runTaskLaterAsynchronously(
                    this, () -> loading.remove(player.getUniqueId()), 200L);
            return;
        }

        if (scenario == Scenario.SILENT_LISTENER) {
            // Everyone belongs to a team by the time they join.
            String team = teamOf(player);
            player.sendMessage(Component.text("Welcome back, " + team.toUpperCase() + " member."));
        }

        giveKit(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (loading.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        loading.remove(event.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- helpers

    private void giveKit(Player player) {
        player.getInventory().addItem(new ItemStack(Material.BREAD, 3));
        player.getInventory().addItem(new ItemStack(Material.WOODEN_SWORD, 1));
    }

    /** The team a player is on, from the roster. */
    private String teamOf(Player player) {
        return getConfig().getString("teams." + player.getName()).toLowerCase(java.util.Locale.ROOT);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
