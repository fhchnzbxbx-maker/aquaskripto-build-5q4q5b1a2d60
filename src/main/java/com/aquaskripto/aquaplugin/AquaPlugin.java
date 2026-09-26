package com.aquaskripto.aquaplugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class AquaPlugin extends JavaPlugin implements Listener {

    private NamespacedKey itemKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> trapped = new HashSet<>();
    private final Set<Block> walls = new HashSet<>();
    private final List<Castle> castles = new ArrayList<>();

    private static final class Castle {
        private final List<Block> blocks;
        private final UUID first;
        private final UUID second;

        private Castle(List<Block> blocks, UUID first, UUID second) {
            this.blocks = blocks;
            this.first = first;
            this.second = second;
        }
    }

    @Override
    public void onEnable() {
        itemKey = new NamespacedKey(this, "sand_castle");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Piaskowy Zamek zostal wlaczony.");
    }

    @Override
    public void onDisable() {
        for (Castle castle : new ArrayList<>(castles)) {
            removeCastle(castle);
        }
        cooldowns.clear();
        trapped.clear();
        walls.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("zamek")) {
            return false;
        }

        if (!sender.hasPermission("aquaplugin.zamek")) {
            sender.sendMessage(ChatColor.RED + "Nie masz uprawnień.");
            return true;
        }

        Player target;
        if (args.length == 1) {
            target = Bukkit.getPlayerExact(args[0]);
        } else if (args.length == 0 && sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(ChatColor.YELLOW + "/zamek [gracz]");
            return true;
        }

        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Gracz musi być online.");
            return true;
        }

        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Różdżka Piaskowego Zamku");
        meta.setLore(List.of(
                ChatColor.GRAY + "Uderz gracza, aby zamknąć was w zamku.",
                ChatColor.YELLOW + "Czas: 7 sekund",
                ChatColor.YELLOW + "Odnowienie: 60 sekund"
        ));
        meta.getPersistentDataContainer().set(
                itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);

        if (!target.getInventory().addItem(item).isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Gracz ma pełny ekwipunek.");
        } else {
            sender.sendMessage(ChatColor.GREEN + "Wydano różdżkę: " + target.getName());
        }
        return true;
    }

    private boolean isWand(ItemStack item) {
        return item.getType() == Material.BLAZE_ROD
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                .has(itemKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)
                || !(event.getEntity() instanceof Player)) {
            return;
        }

        Player attacker = (Player) event.getDamager();
        Player target = (Player) event.getEntity();

        if (!isWand(attacker.getInventory().getItemInMainHand())) {
            return;
        }

        // Różdżka nie zadaje obrażeń.
        event.setCancelled(true);

        if (trapped.contains(attacker.getUniqueId())
                || trapped.contains(target.getUniqueId())) {
            attacker.sendMessage(ChatColor.RED + "Jeden z was jest już w zamku.");
            return;
        }

        long now = System.currentTimeMillis();
        long remaining = cooldowns.getOrDefault(attacker.getUniqueId(), 0L) - now;
        if (remaining > 0) {
            attacker.sendMessage(ChatColor.RED + "Odnowienie: "
                    + ((remaining + 999) / 1000) + " s.");
            return;
        }

        Location a = attacker.getLocation();
        Location b = target.getLocation();
        if (!a.getWorld().equals(b.getWorld())
                || a.distanceSquared(b) > 64
                || Math.abs(a.getBlockY() - b.getBlockY()) > 2) {
            attacker.sendMessage(ChatColor.RED + "Podejdź bliżej gracza.");
            return;
        }

        World world = a.getWorld();
        int minX = Math.min(a.getBlockX(), b.getBlockX()) - 2;
        int maxX = Math.max(a.getBlockX(), b.getBlockX()) + 2;
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()) - 2;
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) + 2;
        int floor = Math.min(a.getBlockY(), b.getBlockY()) - 1;
        int roof = Math.max(a.getBlockY(), b.getBlockY()) + 3;

        if (floor < world.getMinHeight() || roof >= world.getMaxHeight()) {
            attacker.sendMessage(ChatColor.RED + "Za mało miejsca na zamek.");
            return;
        }

        List<Block> toPlace = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = floor; y <= roof; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean shell = x == minX || x == maxX
                            || z == minZ || z == maxZ || y == floor || y == roof;
                    if (!shell) {
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    if (walls.contains(block)
                            || (!block.getType().isAir()
                            && !block.getType().isOccluding())) {
                        attacker.sendMessage(ChatColor.RED
                                + "Brak bezpiecznego miejsca. Spróbuj na otwartym terenie.");
                        return;
                    }
                    if (block.getType().isAir()) {
                        toPlace.add(block);
                    }
                }
            }
        }

        // Nie budujemy ścian w miejscu, gdzie stoi inny gracz.
        for (Player other : world.getPlayers()) {
            Location location = other.getLocation();
            for (Block block : toPlace) {
                double x = location.getX();
                double y = location.getY();
                double z = location.getZ();
                if (x + 0.4 > block.getX() && x - 0.4 < block.getX() + 1
                        && y + 1.9 > block.getY() && y < block.getY() + 1
                        && z + 0.4 > block.getZ() && z - 0.4 < block.getZ() + 1) {
                    attacker.sendMessage(ChatColor.RED
                            + "Inny gracz stoi w miejscu ściany zamku.");
                    return;
                }
            }
        }

        for (Block block : toPlace) {
            block.setType(Material.SANDSTONE, false);
            walls.add(block);
        }

        Castle castle = new Castle(toPlace,
                attacker.getUniqueId(), target.getUniqueId());
        castles.add(castle);
        trapped.add(castle.first);
        trapped.add(castle.second);
        cooldowns.put(attacker.getUniqueId(), now + 60_000L);

        attacker.sendMessage(ChatColor.GOLD + "Piaskowy Zamek! Czas: 7 sekund.");
        target.sendMessage(ChatColor.GOLD + "Zamknięto was w zamku na 7 sekund!");

        getServer().getScheduler().runTaskLater(this, () -> removeCastle(castle), 140L);
    }

    private void removeCastle(Castle castle) {
        for (Block block : castle.blocks) {
            if (block.getType() == Material.SANDSTONE) {
                block.setType(Material.AIR, false);
            }
            walls.remove(block);
        }
        trapped.remove(castle.first);
        trapped.remove(castle.second);
        castles.remove(castle);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (walls.contains(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(walls::contains);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(walls::contains);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (walls.contains(block)
                    || walls.contains(block.getRelative(event.getDirection()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (walls.contains(block)
                    || walls.contains(block.getRelative(event.getDirection().getOppositeFace()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (trapped.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED
                    + "Poczekaj, aż zamek zniknie.");
        }
    }
}