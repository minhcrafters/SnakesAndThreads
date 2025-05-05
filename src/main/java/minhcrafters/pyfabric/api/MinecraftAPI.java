package minhcrafters.pyfabric.api;

import com.google.gson.Gson;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class MinecraftAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftAPI.class);
    private final Gson gson = new Gson();

    private static class ApiCall {
        String action;
        Map<String, Object> args;
    }

    public CompletableFuture<Object> handleApiCall(String action, Map<String, Object> args, ServerCommandSource source, MinecraftServer server) {
        // Ensure execution happens on the server thread for safety
        // For actions returning data, use server.submit. For fire-and-forget, use server.execute.
        return switch (action) {
            // --- Actions without return value (use server.execute) ---
            case "log_info" -> runOnServerExecute(server, () -> log_info(getStringArg(args, "message", "")));
            case "log_warning" -> runOnServerExecute(server, () -> log_warning(getStringArg(args, "message", "")));
            case "log_error" -> runOnServerExecute(server, () -> log_error(getStringArg(args, "message", "")));
            case "send_chat" -> runOnServerExecute(server, () -> send_chat(server, getStringArg(args, "message", "")));
            case "run_command" -> runOnServerExecute(server, () -> {
                String command = getStringArg(args, "command", null);
                if (command != null) run_command(source, command);
                else log_error("run_command API call missing 'command' argument.");
            });
            case "teleport_player" -> runOnServerExecute(server, () -> teleport_player(server,
                    getStringArg(args, "playerName", null),
                    getDoubleArg(args, "x", 0.0),
                    getDoubleArg(args, "y", 0.0),
                    getDoubleArg(args, "z", 0.0),
                    getStringArg(args, "dimensionId", "minecraft:overworld")
            ));

            // --- Actions WITH return value (use server.submit) ---
            case "set_block" -> runOnServerSubmit(server, () -> set_block(server,
                    getIntArg(args, "x", 0),
                    getIntArg(args, "y", 0),
                    getIntArg(args, "z", 0),
                    getStringArg(args, "blockId", null),
                    getStringArg(args, "dimensionId", "minecraft:overworld")
            )); // Returns boolean success
            case "get_block" -> runOnServerSubmit(server, () -> get_block(server,
                    getIntArg(args, "x", 0),
                    getIntArg(args, "y", 0),
                    getIntArg(args, "z", 0),
                    getStringArg(args, "dimensionId", "minecraft:overworld")
            )); // Returns String blockId or null
            case "get_player_pos" -> runOnServerSubmit(server, () -> get_player_pos(server,
                    getStringArg(args, "playerName", null)
            )); // Returns Map<String, Double> or null
            case "get_executor_name" ->
                    runOnServerSubmit(server, source::getName); // Simple access, submit ensures thread safety
            case "get_executor_pos" -> runOnServerSubmit(server, () -> get_entity_pos(source.getEntity()));
            case "get_executor_dimension" -> runOnServerSubmit(server, () -> get_entity_dimension(source.getEntity()));


            default -> {
                log_error("Received unknown API action: " + action);
                yield CompletableFuture.failedFuture(new UnsupportedOperationException("Unknown API action: " + action));
            }
        };
    }

    // Helper for fire-and-forget actions
    private CompletableFuture<Object> runOnServerExecute(MinecraftServer server, Runnable action) {
        server.execute(action);
        return CompletableFuture.completedFuture(null); // Indicate completion without value
    }

    // Helper for actions that compute a result
    private <T> CompletableFuture<Object> runOnServerSubmit(MinecraftServer server, Callable<T> action) {
        // CompletableFuture.supplyAsync ensures the action runs on the server thread
        // and returns a future holding the result or exception.
        // Need to cast Callable result to Object for the return type.
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.call(); // Cast result to Object
            } catch (Exception e) {
                LOGGER.error("Exception during API action execution on server thread", e);
                // Wrap exception to propagate it through the future
                throw new CompletionException(e);
            }
        }, server);
    }

    private String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        if (args == null || !args.containsKey(key) || !(args.get(key) instanceof String)) {
            if (defaultValue == null) log_error("Missing or invalid string argument: " + key);
            return defaultValue;
        }
        return (String) args.get(key);
    }

    private double getDoubleArg(Map<String, Object> args, String key, double defaultValue) {
        if (args == null || !args.containsKey(key) || !(args.get(key) instanceof Number)) {
            log_error("Missing or invalid number argument: " + key);
            return defaultValue;
        }
        return ((Number) args.get(key)).doubleValue();
    }

    private int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        if (args == null || !args.containsKey(key) || !(args.get(key) instanceof Number)) {
            log_error("Missing or invalid integer argument: " + key);
            return defaultValue;
        }
        return ((Number) args.get(key)).intValue();
    }

    private void log_info(String message) {
        LOGGER.info("[PythonScript] {}", message);
    }

    private void log_warning(String message) {
        LOGGER.warn("[PythonScript] {}", message);
    }

    private void log_error(String message) {
        LOGGER.error("[PythonScript] {}", message);
    }

    private void send_chat(MinecraftServer server, String message) {
        if (message == null) return;
        server.getPlayerManager().broadcast(Text.literal(message), false);
    }

    private void run_command(ServerCommandSource source, String command) {
        if (command == null || command.isBlank()) return;
        LOGGER.warn("[PythonScript] Executing command via API: /{} (Source: {})", command, source.getName());
        source.getServer().getCommandManager().executeWithPrefix(source, command);
    }

    private void teleport_player(MinecraftServer server, String playerName, double x, double y, double z, String dimensionId) {
        if (playerName == null || dimensionId == null) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            log_error("Player not found for teleport: " + playerName);
            return;
        }
        Optional<ServerWorld> targetWorldOpt = resolveWorld(server, dimensionId);
        if (targetWorldOpt.isEmpty()) return;

        player.setPos(x, y, z);
        log_info("Teleported player " + playerName + " to " + x + "," + y + "," + z + " in " + dimensionId);
    }


    private boolean set_block(MinecraftServer server, int x, int y, int z, String blockIdStr, String dimensionId) {
        if (blockIdStr == null || dimensionId == null) return false;
        Optional<ServerWorld> worldOpt = resolveWorld(server, dimensionId);
        if (worldOpt.isEmpty()) return false;

        Identifier blockIdentifier = Identifier.tryParse(blockIdStr);
        if (blockIdentifier == null) {
            log_error("Invalid block ID format: " + blockIdStr);
            return false;
        }
        Optional<Block> blockOpt = server.getRegistryManager()
                .getOptional(RegistryKeys.BLOCK).flatMap(optional -> optional.getOptionalValue(blockIdentifier));
        if (blockOpt.isEmpty()) {
            log_error("Block not found: " + blockIdStr);
            return false;
        }

        BlockPos pos = new BlockPos(x, y, z);
        boolean success = worldOpt.get().setBlockState(pos, blockOpt.get().getDefaultState(), 3);
        if (!success) {
            log_warning("Failed to set block at " + pos + " in " + dimensionId);
        }
        return success;
    }

    private String get_block(MinecraftServer server, int x, int y, int z, String dimensionId) {
        Optional<ServerWorld> worldOpt = resolveWorld(server, dimensionId);
        if (worldOpt.isEmpty()) return null;

        BlockPos pos = new BlockPos(x, y, z);
        // Check if block is loaded? world.isChunkLoaded(pos)? Might be needed.
        try {
            Identifier blockId = worldOpt.get().getBlockState(pos).getBlock().getRegistryEntry().registryKey().getValue();
            return blockId.toString();
        } catch (Exception e) { // Catch potential errors if chunk isn't loaded?
            log_error(String.format("Error getting block at %d,%d,%d in %s: %s", x, y, z, dimensionId, e.getMessage()));
            return null;
        }
    }

    // Return map or null
    private Map<String, Double> get_player_pos(MinecraftServer server, String playerName) {
        if (playerName == null) return null;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        return get_entity_pos(player); // Use helper
    }

    // Return map or null
    private Map<String, Double> get_entity_pos(Entity entity) {
        if (entity != null) {
            Vec3d pos = entity.getPos();
            Map<String, Double> result = new HashMap<>();
            result.put("x", pos.x);
            result.put("y", pos.y);
            result.put("z", pos.z);
            return result;
        }
        return null;
    }

    // Return dimension string or null
    private String get_entity_dimension(Entity entity) {
        if (entity != null && entity.getWorld() != null) {
            return entity.getWorld().getRegistryKey().getValue().toString();
        }
        return null;
    }


    private Optional<ServerWorld> resolveWorld(MinecraftServer s, String dimensionId) {
        if (dimensionId == null) return Optional.empty();
        Identifier dimIdentifier = Identifier.tryParse(dimensionId);
        if (dimIdentifier == null) {
            log_error("Invalid dimension ID format: " + dimensionId);
            return Optional.empty();
        }
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimIdentifier);
        ServerWorld world = s.getWorld(worldKey);
        if (world == null) {
            log_error("Dimension not found: " + dimensionId);
            return Optional.empty();
        }
        return Optional.of(world);
    }

    @FunctionalInterface
    public interface Callable<V> {
        V call() throws Exception;
    }
}
