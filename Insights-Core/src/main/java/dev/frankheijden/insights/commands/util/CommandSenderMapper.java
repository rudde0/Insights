package dev.frankheijden.insights.commands.util;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.incendo.cloud.SenderMapper;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class CommandSenderMapper implements SenderMapper<CommandSourceStack, CommandSender> {

    @Override
    public CommandSender map(CommandSourceStack source) {
        return source.getSender();
    }

    @Override
    public CommandSourceStack reverse(CommandSender sender) {
        return new SenderSourceStack(sender, null, sender instanceof Entity entity ? entity : null);
    }

    @SuppressWarnings("NonExtendableApiUsage")
    private record SenderSourceStack(
            CommandSender sender,
            @Nullable Location location,
            @Nullable Entity executor
    ) implements CommandSourceStack {

        private static final SimpleCommandExceptionType noEntity = new SimpleCommandExceptionType(
                new LiteralMessage("No entity is executing this command")
        );
        private static final SimpleCommandExceptionType noPlayer = new SimpleCommandExceptionType(
                new LiteralMessage("No player is executing this command")
        );

        @Override
        public Location getLocation() {
            if (location != null) return location;
            if (executor != null) return executor.getLocation();

            var worlds = Bukkit.getWorlds();
            return new Location(worlds.isEmpty() ? null : worlds.getFirst(), 0, 0, 0); // Best effort lol
        }

        @Override
        public CommandSender getSender() {
            return sender;
        }

        @Override
        public @Nullable Entity getExecutor() {
            return executor;
        }

        @Override
        public Entity getEntityOrThrow() throws CommandSyntaxException {
            if (executor == null) throw noEntity.create();
            return executor;
        }

        @Override
        public Player getPlayerOrThrow() throws CommandSyntaxException {
            if (executor instanceof Player player) return player;
            if (sender instanceof Player player) return player;
            throw noPlayer.create();
        }

        @Override
        public CommandSourceStack withLocation(Location loc) {
            // Must build the new stack directly. Delegating to reverse(sender).withLocation(loc)
            // handed the call straight back to this method and recursed until the stack blew up.
            return new SenderSourceStack(sender, loc, executor);
        }

        @Override
        public CommandSourceStack withExecutor(Entity entity) {
            return new SenderSourceStack(sender, location, entity);
        }
    }
}
