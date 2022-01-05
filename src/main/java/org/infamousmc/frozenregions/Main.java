package org.infamousmc.frozenregions;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bukkit.Material.*;

public final class Main extends JavaPlugin implements Listener {

    public WorldGuardPlugin worldGuardPlugin;
    public static BooleanFlag FROZEN_REGION;
    private final Pattern pattern = Pattern.compile("#[a-fA-F0-9]{6}");

    @Override
    public void onLoad() {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            BooleanFlag flag = new BooleanFlag("frozen-region");
            registry.register(flag);
            FROZEN_REGION = flag;
        } catch (FlagConflictException e) {
            Flag<?> existing = registry.get("frozen-region");
            if (existing instanceof BooleanFlag) {
                FROZEN_REGION = (BooleanFlag) existing;
            }
        }
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        this.getServer().getPluginManager().registerEvents(this, this);

        saveDefaultConfig();

        worldGuardPlugin = getWorldGuard();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    private ArrayList<UUID> enteredRegion = new ArrayList<>();
    private ArrayList<UUID> enteredWater = new ArrayList<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("frozenregions") ||
        label.equalsIgnoreCase("fr")) {
            if (args.length == 0) {
                sendGeneralMessage(sender);
                return true;
            }
            if (args.length == 1) {
                if (args[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    sender.sendMessage(format("&8[#91ceff&l»&8] &fConfig reloaded!"));
                    return true;
                }
            }
            sendGeneralMessage(sender);
        }
        return false;
    }

    public void sendGeneralMessage(CommandSender sender) {
        sender.sendMessage(format("#91ceff&lPlugin: &fFrozenRegions"));
        sender.sendMessage(format("#91ceff&lVersion: &f" + getDescription().getVersion()));
        sender.sendMessage(format("#91ceff&lAuthor: &fdevJordan"));
        sender.sendMessage("");
        sender.sendMessage(format("#91ceff&lSupport: &fPlease report issues on github :)"));
    }

    @EventHandler
    public void quitEvent(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (enteredRegion.contains(uuid))
            enteredRegion.remove(uuid);
        if (enteredWater.contains(uuid))
            enteredWater.remove(uuid);
    }

    @EventHandler
    public void switchGameMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (event.getNewGameMode().equals(GameMode.CREATIVE)) {
            if (enteredRegion.contains(uuid))
                enteredRegion.remove(uuid);
            if (enteredWater.contains(uuid))
                enteredWater.remove(uuid);
        }
    }

    @EventHandler
    public void moveEvent(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!player.getGameMode().equals(GameMode.SURVIVAL)) return;

        enterRegion(player);

        if (!enteredRegion.contains(uuid)) return;
        Material material = event.getPlayer().getLocation().getBlock().getType();

        if (material == WATER) {
            if (enteredWater.contains(uuid)) return;
            enteredWater.add(uuid);
            freezePlayer(player);
            return;
        }
        if (enteredWater.contains(uuid))
            enteredWater.remove(uuid);
    }

    public void enterRegion(Player player) {
        UUID uuid = player.getUniqueId();

        // Get all world regions
        World world = BukkitAdapter.adapt(player.getWorld());
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regions = container.get(world);

        // Get the player's position as BlockVector3
        Location loc = player.getLocation();
        double x = loc.getX(); double y = loc.getY(); double z = loc.getZ();
        BlockVector3 playerLocVector = BlockVector3.at(x,y,z);

        for (ProtectedRegion r : regions.getRegions().values()) {
            // Make sure that the region in the world has our custom flag set to true
            if (r.getFlag(FROZEN_REGION) == null) return;
            if (!r.getFlag(FROZEN_REGION)) return;

            // Make sure the player being checked is inside the region, and if they're outside but were
            // inside remove them from our list.
            if (!r.contains(playerLocVector)) {
                if (enteredRegion.contains(uuid))
                    enteredRegion.remove(uuid);
                return;
            }

            // Only run once for a player that's in the region
            if (enteredRegion.contains(uuid)) return;
            enteredRegion.add(uuid);

            coolPlayer(player);
        }
    }

    public void coolPlayer(Player player) {
        ConfigurationSection cooling = getConfig().getConfigurationSection("Cooling");

        long delay = cooling.getLong("cooling-timer");
        if (cooling.getString("time-unit").equalsIgnoreCase("S")) {
            delay = delay * 20;
        }
        if (cooling.getString("time-unit").equalsIgnoreCase("M")) {
            delay = delay * 1200;
        }
        if (cooling.getString("time-unit").equalsIgnoreCase("H")) {
            delay = delay * 72000;
        }
        new BukkitRunnable() {
            int i = 0;
            @Override
            public void run() {
                if (!enteredRegion.contains(player.getUniqueId())) {
                    cancel();
                    return;
                }

                int amp = Math.floorDiv(i, cooling.getInt("slowness-increments"));
                PotionEffect slowness = new PotionEffect(PotionEffectType.SLOW,
                        cooling.getInt("persistent-delay"), amp, false, false, false);
                if (i >= cooling.getInt("damage-timer")) {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                            new TextComponent(format(cooling.getString("damage-actionbar"))));
                    player.damage(1);
                }

                if (i <= cooling.getInt("damage-timer") - 1) {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                            new TextComponent(format(cooling.getString("warning-actionbar"))));
                }
                player.addPotionEffect(slowness);

                i++;
            }
        }.runTaskTimer(this, delay, 20);
    }

    public void freezePlayer(Player player) {
        ConfigurationSection freezing = getConfig().getConfigurationSection("Freezing");

        new BukkitRunnable() {
            int i = 0;
            @Override
            public void run() {
                if (!enteredWater.contains(player.getUniqueId())) {
                    cancel();
                    return;
                }

                int amp = Math.floorDiv(i, freezing.getInt("slowness-increments"));
                PotionEffect slowness = new PotionEffect(PotionEffectType.SLOW,
                        freezing.getInt("persistent-delay"), amp, false, false, false);

                if (i > freezing.getInt("slowness-timer")) {
                    player.addPotionEffect(slowness);
                }

                player.setFreezeTicks(i);
//                if (i != 140)
                i++;
            }
        }.runTaskTimer(this, freezing.getLong("freezing-timer"), 1);
    }

    public WorldGuardPlugin getWorldGuard() {
        Plugin plugin = this.getServer().getPluginManager().getPlugin("WorldGuard");

        if (plugin == null || !(plugin instanceof WorldGuardPlugin)) {
            return null;
        }

        return (WorldGuardPlugin) plugin;
    }

    private String format(String message) {

        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) {
            String color = message.substring(matcher.start(), matcher.end());
            message = message.replace(color, ChatColor.of(color) + "");
            CharSequence input;
            matcher = pattern.matcher(message);
        }

        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
