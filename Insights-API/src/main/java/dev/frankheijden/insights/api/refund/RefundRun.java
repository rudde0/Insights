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
    private static final int MAX_SPOT_HEIGHT = 8;

    private final GracefulRefund refund;
    private final InsightsPlugin plugin;
    private final RefundConfig cfg;
    private final World world;
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int maxY;
    private final int[][] found;
    private final int rememberedChest;

    RefundRun(
            GracefulRefund refund,
            RefundConfig cfg,
            World world,
            int chunkX,
            int chunkZ,
            int minY,
            int maxY,
            int[][] found,
            int rememberedChest
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
        this.rememberedChest = rememberedChest;
    }

    /**
     * Removes the blocks beyond the limit and hands them back in a chest.
     */
    Result run() {
        RefundGroup[] groups = cfg.groups();
        int[] excess = new int[groups.length];
        List<List<Block>> candidates = new ArrayList<>(groups.length);
        StringJoiner exceeded = new StringJoiner(", ");
        for (RefundGroup group : groups) {
            List<Block> verified = verify(group);
            int groupExcess = verified.size() - group.limit();
            excess[group.index()] = Math.max(0, groupExcess);
            candidates.add(groupExcess > 0 ? removable(group, verified) : List.of());
            if (groupExcess > 0) {
                exceeded.add(EnumUtils.pretty(group.material()) + " " + verified.size() + "/" + group.limit());
            }
        }
        if (exceeded.length() == 0) return Result.NOTHING;

        // Refunds go into the chest of the previous refund in this chunk if it has room, or else into a new chest.
        Chest existingChest = findRefundChest();
        List<Planned> plan = existingChest == null ? List.of() : plan(candidates, excess, existingChest.getBlockInventory());
        Block spot = null;
        if (plan.isEmpty()) {
            existingChest = null;
            plan = plan(candidates, excess, null);
            if (plan.isEmpty()) {
                logSkipped(exceeded, "none of those blocks can be handed back as a whole (named or plugin-tagged "
                        + "containers, containers with loot yet to generate, blocks spanning several blocks or items)");
                return Result.NOTHING;
            }

            spot = findSpot(plan);
            if (spot == null) {
                logSkipped(exceeded, "there is no room for a chest next to the blocks to remove");
                return Result.NOTHING;
            }
        }

        return execute(plan, excess, existingChest, spot);
    }

    private void logSkipped(StringJoiner exceeded, String reason) {
        if (cfg.log()) {
            plugin.getLogger().warning("Graceful refund: chunk " + chunkX + ", " + chunkZ + " in '" + world.getName()
                    + "' holds more than allowed (" + exceeded + "), but nothing was refunded: " + reason + ".");
        }
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
            Block block = blockAt(position);
            // Regions of addons are limited as a whole, the limit of a chunk does not apply to them.
            if (group.contains(block.getType()) && !isInRegion(block)) {
                verified.add(block);
            }
        }
        return verified;
    }

    private Block blockAt(int position) {
        return world.getBlockAt(
                (chunkX << 4) + BlockPositions.localX(position),
                minY + BlockPositions.relativeY(position),
                (chunkZ << 4) + BlockPositions.localZ(position)
        );
    }

    private int positionOf(Block block) {
        return BlockPositions.pack(block.getX(), block.getY() - minY, block.getZ());
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

    /**
     * Returns the chest of the previous refund in this chunk if it is still there and has room.
     * Only its remembered position is looked at, the chunk is not searched for it.
     */
    private Chest findRefundChest() {
        if (rememberedChest < 0) return null;

        Block block = blockAt(rememberedChest);
        if (block.getType() != Material.CHEST || !(block.getState(false) instanceof Chest chest)) return null;
        if (!chest.getPersistentDataContainer().has(refund.getChestKey(), PersistentDataType.BYTE)) return null;
        return chest.getBlockInventory().firstEmpty() >= 0 ? chest : null;
    }

    /**
     * Picks the blocks to remove, as many as fit into the chest (a new one if null).
     */
    private List<Planned> plan(List<List<Block>> candidates, int[] excess, Inventory chestInventory) {
        ChestSpace space = new ChestSpace(chestInventory);
        List<Planned> plan = new ArrayList<>();
        for (RefundGroup group : cfg.groups()) {
            int planned = 0;
            for (Block block : candidates.get(group.index())) {
                if (planned >= excess[group.index()] || plan.size() >= cfg.maxBlocksPerRun()) break;

                List<ItemStack> items = refundItems(block, group);
                if (items == null) continue;
                if (!space.reserve(items)) return plan;

                plan.add(new Planned(block, group));
                planned++;
            }
        }
        return plan;
    }

    /**
     * Finds a place for a new chest near the blocks to remove: where one of them is (those are empty once removed),
     * or else an empty spot above or next to one. Of all these the least disruptive spot is picked, a chest may only
     * never end up where it would break something (a cactus next to it).
     */
    private Block findSpot(List<Planned> plan) {
        Set<Long> cleared = new HashSet<>(plan.size());
        for (Planned planned : plan) {
            cleared.add(keyOf(planned.block()));
        }

        Set<Long> seen = new HashSet<>();
        Block best = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (Block candidate : spotCandidates(plan)) {
            if (!seen.add(keyOf(candidate))) continue;

            int penalty = spotPenalty(candidate, cleared);
            if (penalty >= 0 && penalty < bestPenalty) {
                best = candidate;
                bestPenalty = penalty;
                if (penalty == 0) break;
            }
        }
        return best;
    }

    /**
     * Returns the spots to consider for a chest, closest to the blocks to remove first.
     */
    private List<Block> spotCandidates(List<Planned> plan) {
        List<Block> candidates = new ArrayList<>();
        for (Planned planned : plan) {
            candidates.add(planned.block());
        }
        for (Planned planned : plan) {
            // The first empty block above, e.g. on top of the floor covering a row of hoppers.
            Block above = planned.block().getRelative(BlockFace.UP);
            for (int i = 0; i < MAX_SPOT_HEIGHT && above.getY() < maxY; i++) {
                if (isAir(above.getType())) {
                    candidates.add(above);
                    break;
                }
                above = above.getRelative(BlockFace.UP);
            }
        }
        for (Planned planned : plan) {
            for (BlockFace face : SIDES) {
                Block side = planned.block().getRelative(face);
                if (isInChunk(side) && isAir(side.getType())) {
                    candidates.add(side);
                }
            }
        }
        return candidates;
    }

    /**
     * Returns how disruptive a chest at the given spot would be (0 being not at all), or -1 if it can't go there.
     * Spots of blocks to remove count as empty, as do their neighbours which are removed.
     */
    private int spotPenalty(Block spot, Set<Long> cleared) {
        if (!isInChunk(spot) || spot.getY() < minY || spot.getY() >= maxY) return -1;
        if (!cleared.contains(keyOf(spot)) && !isAir(spot.getType())) return -1;
        if (isInRegion(spot)) return -1;

        int penalty = 0;
        for (BlockFace face : SIDES) {
            Block side = spot.getRelative(face);

            // Reading a block of an unloaded chunk would load it.
            if (!world.isChunkLoaded(side.getX() >> 4, side.getZ() >> 4)) return -1;
            if (cleared.contains(keyOf(side))) continue;

            // A cactus breaks next to a chest. Chests next to each other are merely easily mistaken for one.
            Material type = side.getType();
            if (type == Material.CACTUS) return -1;
            if (type == Material.CHEST || type == Material.TRAPPED_CHEST) penalty += 1;
        }

        // A hopper below moves the refund out of the chest (into the owner's own hoppers),
        // and farmland below turns into dirt.
        Block below = spot.getRelative(BlockFace.DOWN);
        if (below.getY() >= minY && !cleared.contains(keyOf(below))) {
            Material type = below.getType();
            if (type == Material.HOPPER) penalty += 8;
            if (type == Material.FARMLAND) penalty += 2;
        }

        // A chest with a solid block on top can't be opened, only broken (which drops its contents).
        Block above = spot.getRelative(BlockFace.UP);
        if (above.getY() < maxY && !cleared.contains(keyOf(above)) && above.getType().isOccluding()) penalty += 4;
        return penalty;
    }

    private boolean isInChunk(Block block) {
        return block.getX() >> 4 == chunkX && block.getZ() >> 4 == chunkZ;
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

    private Result execute(List<Planned> plan, int[] excess, Chest existingChest, Block spot) {
        Inventory chestInventory = null;
        Block chestBlock;
        List<Planned> order = new ArrayList<>(plan);
        if (existingChest != null) {
            chestInventory = existingChest.getBlockInventory();
            chestBlock = existingChest.getBlock();
        } else {
            chestBlock = spot;
            int spotIndex = indexOf(order, spot);
            if (spotIndex < 0) {
                // The spot is empty already, the chest can be placed right away. A block to remove right below it
                // is removed first, as it was only fine to have below the chest because it goes (e.g. a hopper).
                chestInventory = placeChest(spot);
                if (chestInventory == null) return Result.NOTHING;
                int belowIndex = indexOf(order, spot.getRelative(BlockFace.DOWN));
                if (belowIndex >= 0) {
                    order.add(0, order.remove(belowIndex));
                }
            } else {
                // The spot is taken by a block to remove, which is removed first.
                order.add(0, order.remove(spotIndex));
            }
        }

        ChestSpace space = new ChestSpace(chestInventory);
        Location chestLocation = chestBlock.getLocation();
        RefundGroup[] groups = cfg.groups();
        int[] allowed = excess.clone();
        int[] removed = new int[groups.length];
        int[] othersRemovedAtCount = new int[groups.length];
        int total = 0;
        Map<Material, Integer> refunded = new EnumMap<>(Material.class);
        StringJoiner positions = new StringJoiner(" ");
        for (Planned planned : order) {
            RefundGroup group = planned.group();
            int index = group.index();

            // Removing blocks may affect blocks of other groups (e.g. through physics), so a group is counted again
            // if blocks of other groups were removed since it was counted last, the first count being at the start.
            int othersRemoved = total - removed[index];
            if (othersRemoved > othersRemovedAtCount[index]) {
                allowed[index] = Math.min(allowed[index], removed[index] + verify(group).size() - group.limit());
                othersRemovedAtCount[index] = othersRemoved;
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

            // The spot must go first, nothing is removed before the chest has a place.
            if (chestInventory == null && keyOf(block) != keyOf(spot)) break;
            if (!space.reserve(items)) break;

            remove(block, type);
            removed[index]++;
            total++;
            refunded.merge(group.material(), 1, Integer::sum);
            positions.add(block.getX() + "," + block.getY() + "," + block.getZ());

            if (chestInventory == null) {
                chestInventory = placeChest(spot);
                if (chestInventory == null) {
                    plugin.getLogger().severe("Graceful refund could not place a chest at " + format(chestLocation)
                            + ", dropping the refund of " + format(block.getLocation()) + " instead.");
                    drop(items, block.getLocation());
                    return new Result(false, -1);
                }
            }
            store(chestInventory, items, chestLocation);
        }

        if (total == 0) {
            // Nothing turned out to be removable after all, a chest placed for it goes again (it is still empty).
            if (existingChest == null && chestInventory != null && chestInventory.isEmpty()) {
                chestBlock.setType(Material.AIR, false);
                updateCache(chestLocation, Material.CHEST, Material.AIR);
            }
            return Result.NOTHING;
        }

        boolean remaining = false;
        for (RefundGroup group : groups) {
            remaining |= removed[group.index()] < allowed[group.index()];
        }

        announce(refunded, total, chestLocation, positions.toString());
        return new Result(remaining, positionOf(chestBlock));
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

        // A block which would not even fit into an empty chest can never be refunded.
        return ChestSpace.slotsNeeded(items) <= CHEST_SIZE ? items : null;
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
            // Can't happen, room for the items was reserved right before.
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

    /**
     * The outcome of a run.
     *
     * @param again whether blocks were removed while more remain beyond the limit, i.e. whether to run again
     * @param chest the position of the chest refunded into (see {@link BlockPositions}), or -1 if none
     */
    record Result(boolean again, int chest) {

        static final Result NOTHING = new Result(false, -1);
    }

    private record Planned(Block block, RefundGroup group) {}

    /**
     * Keeps track of the room left in a chest without copying it. Counts pessimistically: every stack of contents
     * is taken to need slots of its own, only the refunded block items fill up stacks of their own first. So what is
     * counted to fit always fits (it merely may leave room unused).
     */
    private static final class ChestSpace {

        private final Map<Material, Integer> blockItems = new EnumMap<>(Material.class);
        private int freeSlots = 0;

        private ChestSpace(Inventory inventory) {
            if (inventory == null) {
                freeSlots = CHEST_SIZE;
                return;
            }
            for (ItemStack stack : inventory.getStorageContents()) {
                if (stack == null || stack.getType().isAir()) freeSlots++;
            }
        }

        /**
         * Returns the slots the items of a block need at most on their own, the first item being the block itself.
         */
        private static int slotsNeeded(List<ItemStack> items) {
            int slots = 1;
            for (int i = 1; i < items.size(); i++) {
                slots += slotsOf(items.get(i));
            }
            return slots;
        }

        private static int slotsOf(ItemStack stack) {
            int maxStackSize = Math.max(1, stack.getMaxStackSize());
            return Math.max(1, (stack.getAmount() + maxStackSize - 1) / maxStackSize);
        }

        /**
         * Reserves room for the items of a block (the first item being the block itself), if there is enough.
         */
        private boolean reserve(List<ItemStack> items) {
            ItemStack blockItem = items.get(0);
            int added = blockItems.getOrDefault(blockItem.getType(), 0);
            int needed = slotsNeeded(items) - (added % Math.max(1, blockItem.getMaxStackSize()) == 0 ? 0 : 1);
            if (needed > freeSlots) return false;

            freeSlots -= needed;
            blockItems.put(blockItem.getType(), added + 1);
            return true;
        }
    }
}
