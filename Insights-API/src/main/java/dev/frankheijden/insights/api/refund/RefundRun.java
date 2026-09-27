package dev.frankheijden.insights.api.refund;

import dev.frankheijden.insights.api.InsightsPlugin;
import dev.frankheijden.insights.api.concurrent.storage.Storage;
import dev.frankheijden.insights.api.config.Messages;
import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import dev.frankheijden.insights.api.utils.ChunkUtils;
import dev.frankheijden.insights.api.utils.EnumUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Candle;
import org.bukkit.block.data.type.SeaPickle;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.data.type.TurtleEgg;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A single refund of a chunk. Runs on the thread owning the chunk, from start to end within one tick,
 * so nothing can change the chunk while it runs apart from what it does itself.
 */
final class RefundRun {

    private static final int CHEST_SIZE = 27;
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final GracefulRefund refund;
    private final InsightsPlugin plugin;
    private final RefundConfig cfg;
    private final World world;
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int maxY;
    private final int[][] found;

    RefundRun(
            GracefulRefund refund,
            RefundConfig cfg,
            World world,
            int chunkX,
            int chunkZ,
            int minY,
            int maxY,
            int[][] found
    ) {
        this.refund = refund;
        this.plugin = refund.getPlugin();
        this.cfg = cfg;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.maxY = maxY;
        this.found = found;
    }

    /**
     * Removes the blocks beyond the limit and hands them back in a chest.
     *
     * @return whether blocks were removed while more remain beyond the limit, i.e. whether to run again.
     */
    boolean run() {
        RefundGroup[] groups = cfg.groups();
        int[] excess = new int[groups.length];
        List<List<Block>> candidates = new ArrayList<>(groups.length);
        boolean exceeded = false;
        for (RefundGroup group : groups) {
            List<Block> verified = verify(group);
            int groupExcess = verified.size() - group.limit();
            excess[group.index()] = Math.max(0, groupExcess);
            candidates.add(groupExcess > 0 ? removable(group, verified) : List.of());
            exceeded |= groupExcess > 0;
        }
        if (!exceeded) return false;

        // Refunds go into a chest of an earlier refund in this chunk if it has room, or else into a new chest.
        Chest existingChest = findRefundChest();
        List<Planned> plan = existingChest == null ? List.of() : plan(candidates, excess, existingChest.getBlockInventory());
        Block spot = null;
        if (plan.isEmpty()) {
            existingChest = null;
            plan = plan(candidates, excess, null);
            if (plan.isEmpty()) return false;

            spot = findSpot(plan);
            if (spot == null) {
                if (cfg.log()) {
                    plugin.getLogger().warning("Graceful refund: chunk " + chunkX + ", " + chunkZ + " in '"
                            + world.getName() + "' holds more than allowed, but there is no room for a chest.");
                }
                return false;
            }
        }

        return execute(plan, excess, existingChest, spot);
    }

    /**
     * Returns the blocks of the group which are still there, excluding those inside regions of addons.
     * Positions come from the snapshot, so blocks placed since are missing: this never counts too many.
     */
    private List<Block> verify(RefundGroup group) {
        int[] positions = found[group.index()];
        if (positions.length <= group.limit()) return List.of();

        List<Block> verified = new ArrayList<>(positions.length);
        for (int position : positions) {
            Block block = world.getBlockAt(
                    (chunkX << 4) + BlockPositions.localX(position),
                    minY + BlockPositions.relativeY(position),
                    (chunkZ << 4) + BlockPositions.localZ(position)
            );
            // Regions of addons are limited as a whole, the limit of a chunk does not apply to them.
            if (group.contains(block.getType()) && !isInRegion(block)) {
                verified.add(block);
            }
        }
        return verified;
    }

    /**
     * Returns the blocks which can be removed without anything of the group depending on them, highest first.
     */
    private List<Block> removable(RefundGroup group, List<Block> verified) {
        List<Block> removable = new ArrayList<>();
        for (Block block : verified) {
            if (isTopmost(block, group)) {
                removable.add(block);
            }
        }
        removable.sort(Comparator.comparingInt(Block::getY).reversed());
        return removable;
    }

    /**
     * Returns whether the block above is not of the group, so removing this block does not break
     * another block of the group (e.g. a cactus on top of a cactus), dropping it as item on top of the refund.
     */
    private boolean isTopmost(Block block, RefundGroup group) {
        return block.getY() + 1 >= maxY || !group.contains(block.getRelative(BlockFace.UP).getType());
    }

    private boolean isInRegion(Block block) {
        return plugin.getAddonManager().getRegion(block.getLocation()).isPresent();
    }

    private Chest findRefundChest() {
        var chunk = world.getChunkAt(chunkX, chunkZ);
        for (BlockState state : chunk.getTileEntities(block -> block.getType() == Material.CHEST, false)) {
            if (state instanceof Chest chest
                    && chest.getPersistentDataContainer().has(refund.getChestKey(), PersistentDataType.BYTE)
                    && chest.getBlockInventory().firstEmpty() >= 0) {
                return chest;
            }
        }
        return null;
    }

    /**
     * Picks the blocks to remove, as many as fit into the chest (a new one if null).
     */
    private List<Planned> plan(List<List<Block>> candidates, int[] excess, Inventory chestInventory) {
        Inventory simulation = copyOf(chestInventory);
        List<Planned> plan = new ArrayList<>();
        for (RefundGroup group : cfg.groups()) {
            int planned = 0;
            for (Block block : candidates.get(group.index())) {
                if (planned >= excess[group.index()] || plan.size() >= cfg.maxBlocksPerRun()) break;

                List<ItemStack> items = refundItems(block, group);
                if (items == null) continue;
                if (!fits(simulation, items)) return plan;

                simulation.addItem(copyOf(items));
                plan.add(new Planned(block, group));
                planned++;
            }
        }
        return plan;
    }

    /**
     * Finds a place for a new chest: preferably where a removed block was, else right above one.
     */
    private Block findSpot(List<Planned> plan) {
        Set<Long> cleared = new HashSet<>(plan.size());
        for (Planned planned : plan) {
            cleared.add(keyOf(planned.block()));
        }

        for (Planned planned : plan) {
            if (isSuitableSpot(planned.block(), cleared)) return planned.block();
        }
        for (Planned planned : plan) {
            Block above = planned.block().getRelative(BlockFace.UP);
            if (above.getY() < maxY && isAir(above.getType()) && isSuitableSpot(above, cleared)) return above;
        }
        return null;
    }

    /**
     * Checks whether a chest at given (empty or cleared) spot neither breaks nor drains anything, and can be opened.
     */
    private boolean isSuitableSpot(Block spot, Set<Long> cleared) {
        if (isInRegion(spot)) return false;

        // A hopper below would drain the chest, and farmland turns into dirt below it.
        Block below = spot.getRelative(BlockFace.DOWN);
        if (below.getY() >= minY && !cleared.contains(keyOf(below))) {
            Material type = below.getType();
            if (type == Material.HOPPER || type == Material.FARMLAND) return false;
        }

        // A chest can't be opened with a solid block on top of it.
        Block above = spot.getRelative(BlockFace.UP);
        if (above.getY() < maxY && !cleared.contains(keyOf(above)) && above.getType().isOccluding()) return false;

        for (BlockFace face : SIDES) {
            Block side = spot.getRelative(face);

            // Reading a block of an unloaded chunk would load it.
            if (!world.isChunkLoaded(side.getX() >> 4, side.getZ() >> 4)) return false;
            if (cleared.contains(keyOf(side))) continue;

            // A cactus breaks next to a chest, and chests next to each other are easily mistaken for one.
            Material type = side.getType();
            if (type == Material.CACTUS || type == Material.CHEST || type == Material.TRAPPED_CHEST) return false;
        }
        return true;
    }

    /**
     * Returns a key unique to the position of the block (within the bounds of a world).
     */
    private static long keyOf(Block block) {
        return (long) block.getX() << 38 | (block.getY() & 0xFFFL) << 26 | block.getZ() & 0x3FFFFFFL;
    }

    private static boolean isAir(Material type) {
        return type == Material.AIR || type == Material.CAVE_AIR;
    }

    private boolean execute(List<Planned> plan, int[] excess, Chest existingChest, Block spot) {
        Inventory chestInventory = null;
        Location chestLocation;
        List<Planned> order = new ArrayList<>(plan);
        if (existingChest != null) {
            chestInventory = existingChest.getBlockInventory();
            chestLocation = existingChest.getLocation();
        } else {
            chestLocation = spot.getLocation();
            int spotIndex = indexOf(order, spot);
            if (spotIndex < 0) {
                // The spot is above a block to remove, the chest can be placed right away. The block below is
                // removed first, it was only fine to have below the chest because it goes (e.g. a hopper).
                chestInventory = placeChest(spot);
                if (chestInventory == null) return false;
                order.add(0, order.remove(indexOf(order, spot.getRelative(BlockFace.DOWN))));
            } else {
                // The spot is taken by a block to remove, which is removed first.
                order.add(0, order.remove(spotIndex));
            }
        }

        RefundGroup[] groups = cfg.groups();
        int[] allowed = excess.clone();
        boolean[] recounted = new boolean[groups.length];
        int[] removed = new int[groups.length];
        Map<Material, Integer> refunded = new EnumMap<>(Material.class);
        StringJoiner positions = new StringJoiner(" ");
        for (Planned planned : order) {
            RefundGroup group = planned.group();
            int index = group.index();

            // Counted again before touching a group, removing other blocks may have affected it (e.g. through physics).
            if (!recounted[index]) {
                allowed[index] = Math.min(allowed[index], verify(group).size() - group.limit());
                recounted[index] = true;
            }
            if (removed[index] >= allowed[index]) continue;

            Block block = planned.block();
            Material type = block.getType();
            if (!group.contains(type) || !isTopmost(block, group)) continue;

            // Viewers are closed before the contents are taken, so nothing is taken out or put in after.
            if (block.getState(false) instanceof Container container) {
                container.getInventory().close();
            }

            List<ItemStack> items = refundItems(block, group);
            if (items == null) continue;
            if (chestInventory == null) {
                // The spot must go first, nothing is removed before the chest has a place.
                if (keyOf(block) != keyOf(spot) || !fits(null, items)) break;
            } else if (!fits(chestInventory, items)) {
                break;
            }

            remove(block, type);
            removed[index]++;
            refunded.merge(group.material(), 1, Integer::sum);
            positions.add(block.getX() + "," + block.getY() + "," + block.getZ());

            if (chestInventory == null) {
                chestInventory = placeChest(spot);
                if (chestInventory == null) {
                    plugin.getLogger().severe("Graceful refund could not place a chest at " + format(chestLocation)
                            + ", dropping the refund of " + format(block.getLocation()) + " instead.");
                    drop(items, block.getLocation());
                    break;
                }
            }
            store(chestInventory, items, chestLocation);
        }

        int total = 0;
        boolean remaining = false;
        for (RefundGroup group : groups) {
            int index = group.index();
            total += removed[index];
            remaining |= removed[index] < allowed[index];
        }
        if (total == 0) return false;

        announce(refunded, total, chestLocation, positions.toString());
        return remaining;
    }

    private static int indexOf(List<Planned> plan, Block block) {
        for (int i = 0; i < plan.size(); i++) {
            if (keyOf(plan.get(i).block()) == keyOf(block)) return i;
        }
        return -1;
    }

    /**
     * Returns the items handed back for the block (the block and anything it contains),
     * or null if the block can't be handed back as a whole.
     */
    private List<ItemStack> refundItems(Block block, RefundGroup group) {
        if (!isSingleBlock(block.getBlockData())) return null;

        List<ItemStack> items = new ArrayList<>();
        items.add(new ItemStack(group.refundMaterial()));

        BlockState state = block.getState(false);
        if (state instanceof TileState tile) {
            // Blocks keeping more than an inventory (e.g. spawners, signs) can't be handed back as an item,
            // chests may be half of a double chest and shulker boxes keep their contents as item.
            if (!(tile instanceof Container container) || tile instanceof Chest || tile instanceof ShulkerBox) return null;

            // Named containers, or containers other plugins keep data on, may well be custom blocks.
            if (container.customName() != null || !tile.getPersistentDataContainer().isEmpty()) return null;

            // Loot which has not been generated yet would be lost.
            if (container instanceof Lootable lootable && lootable.getLootTable() != null) return null;

            for (ItemStack stack : container.getInventory().getContents()) {
                if (stack != null && !stack.getType().isAir()) {
                    items.add(stack.clone());
                }
            }
        }
        return items.size() <= CHEST_SIZE ? items : null;
    }

    /**
     * Returns whether the block is exactly one of the item it is placed with.
     * Doors, beds and tall plants span two blocks, and double slabs, sea pickles etc. hold several items.
     */
    private static boolean isSingleBlock(BlockData data) {
        if (data instanceof Bisected && !(data instanceof Stairs) && !(data instanceof TrapDoor)) return false;
        if (data instanceof Bed) return false;
        if (data instanceof Slab slab) return slab.getType() != Slab.Type.DOUBLE;
        if (data instanceof SeaPickle seaPickle) return seaPickle.getPickles() == 1;
        if (data instanceof Candle candle) return candle.getCandles() == 1;
        if (data instanceof TurtleEgg turtleEgg) return turtleEgg.getEggs() == 1;
        if (data instanceof Snow snow) return snow.getLayers() == 1;
        return true;
    }

    private void remove(Block block, Material type) {
        if (block.getState(false) instanceof Container container) {
            container.getInventory().clear();
        }
        block.setType(Material.AIR, true);
        updateCache(block.getLocation(), type, Material.AIR);
    }

    private Inventory placeChest(Block spot) {
        Material previous = spot.getType();
        if (!isAir(previous)) return null;

        spot.setType(Material.CHEST, false);
        if (!(spot.getState() instanceof Chest chest)) return null;

        chest.getPersistentDataContainer().set(refund.getChestKey(), PersistentDataType.BYTE, (byte) 1);
        plugin.getMessages().getMessage(Messages.Key.GRACEFUL_REFUND_CHEST_NAME).toComponent()
                .ifPresent(name -> chest.customName(name));
        chest.update(true, false);
        updateCache(spot.getLocation(), previous, Material.CHEST);

        return spot.getState(false) instanceof Chest placed ? placed.getBlockInventory() : null;
    }

    private void store(Inventory chestInventory, List<ItemStack> items, Location chestLocation) {
        Map<Integer, ItemStack> leftover = chestInventory.addItem(items.toArray(new ItemStack[0]));
        if (!leftover.isEmpty()) {
            // Can't happen, whether the items fit was checked right before.
            plugin.getLogger().warning("Graceful refund: the chest at " + format(chestLocation)
                    + " was full, dropping the rest of the refund next to it.");
            drop(new ArrayList<>(leftover.values()), chestLocation);
        }
    }

    private void drop(List<ItemStack> items, Location location) {
        Location dropLocation = location.clone().add(0.5, 1, 0.5);
        for (ItemStack item : items) {
            world.dropItem(dropLocation, item);
        }
    }

    /**
     * Returns whether all items fit into the inventory (an empty chest if null), without changing it.
     */
    private static boolean fits(Inventory inventory, List<ItemStack> items) {
        return copyOf(inventory).addItem(copyOf(items)).isEmpty();
    }

    private static Inventory copyOf(Inventory inventory) {
        Inventory copy = Bukkit.createInventory(null, inventory == null ? CHEST_SIZE : inventory.getSize());
        if (inventory != null) {
            ItemStack[] contents = inventory.getContents();
            for (int i = 0; i < contents.length; i++) {
                if (contents[i] != null) {
                    contents[i] = contents[i].clone();
                }
            }
            copy.setContents(contents);
        }
        return copy;
    }

    private static ItemStack[] copyOf(List<ItemStack> items) {
        ItemStack[] copy = new ItemStack[items.size()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = items.get(i).clone();
        }
        return copy;
    }

    /**
     * Keeps the cached counts in line with the changes made, as no event is fired for them.
     */
    private void updateCache(Location location, Material from, Material to) {
        Consumer<Storage> update = storage -> {
            storage.modify(ScanObject.of(from), -1);
            storage.modify(ScanObject.of(to), 1);
        };
        plugin.getWorldStorage().getWorld(world.getUID()).get(ChunkUtils.getKey(location)).ifPresent(update);
        plugin.getAddonManager().getRegion(location)
                .flatMap(region -> plugin.getAddonStorage().get(region.getKey()))
                .ifPresent(update);
    }

    private void announce(Map<Material, Integer> refunded, int total, Location chestLocation, String positions) {
        StringJoiner blocks = new StringJoiner(", ");
        refunded.forEach((material, amount) -> blocks.add(amount + "x " + EnumUtils.pretty(material)));

        if (cfg.log()) {
            plugin.getLogger().info("Graceful refund removed " + blocks + " beyond the limit of chunk " + chunkX + ", "
                    + chunkZ + " in '" + world.getName() + "' (" + positions + "), refunded into the chest at "
                    + format(chestLocation) + ".");
        }

        int radius = cfg.notifyRadius();
        if (radius <= 0) return;

        Messages.Message message = plugin.getMessages().getMessage(Messages.Key.GRACEFUL_REFUND_NOTIFICATION).addTemplates(
                Messages.tagOf("blocks", blocks.toString()),
                Messages.tagOf("amount", total),
                Messages.tagOf("x", chestLocation.getBlockX()),
                Messages.tagOf("y", chestLocation.getBlockY()),
                Messages.tagOf("z", chestLocation.getBlockZ()),
                Messages.tagOf("world", world.getName())
        );
        Location center = chestLocation.toCenterLocation();
        double radiusSquared = (double) radius * radius;
        for (Map.Entry<UUID, Player> entry : plugin.getPlayerList()) {
            Player player = entry.getValue();
            if (!world.equals(player.getWorld())) continue;

            // Players are owned by the thread of the region they are in, which may not be this one.
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                notifyIfNearby(player, center, radiusSquared, message);
            } else {
                player.getScheduler().run(plugin, task -> notifyIfNearby(player, center, radiusSquared, message), null);
            }
        }
    }

    private static void notifyIfNearby(Player player, Location center, double radiusSquared, Messages.Message message) {
        if (player.isOnline()
                && center.getWorld().equals(player.getWorld())
                && player.getLocation().distanceSquared(center) <= radiusSquared) {
            message.sendTo(player);
        }
    }

    private static String format(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private record Planned(Block block, RefundGroup group) {}
}
