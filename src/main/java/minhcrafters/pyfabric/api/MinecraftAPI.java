package minhcrafters.pyfabric.api;

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
import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class MinecraftAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftAPI.class);

    private final MinecraftServer server;
    private final ServerCommandSource commandSource;

    public MinecraftAPI(MinecraftServer server, ServerCommandSource source) {
        this.server = server;
        this.commandSource = source;
    }

    private <T> T runOnServerThreadSubmit(Function<MinecraftServer, T> action) {
        if (server == null) {
            log_error("Cannot submit action to server thread: Server instance is null.");
            return null;
        }
        if (server.isOnThread()) {
            try {
                return action.apply(server);
            } catch (Exception e) {
                log_error("Exception executing action directly on server thread: " + e.getMessage());
                LOGGER.error("Exception executing action directly:", e);
                throw e;
            }
        } else {
            try {
                return server.submit(() -> {
                    try {
                        return action.apply(server);
                    } catch (Exception e) {
                        log_error("Exception during submitted server thread action: " + e.getMessage());
                        LOGGER.error("Exception during submitted action:", e);
                        throw new RuntimeException(e);
                    }
                }).join();
            } catch (Exception e) {
                log_error("Error submitting/joining server thread task: " + e.getMessage());
                LOGGER.error("Error submitting/joining task:", e);
                throw new RuntimeException(e);
            }
        }
    }

    private void runOnServerThreadExecute(Consumer<MinecraftServer> action) {
        if (server == null) {
            log_error("Cannot execute action on server thread: Server instance is null.");
            return;
        }
        if (server.isOnThread()) {
            try {
                action.accept(server);
            } catch (Exception e) {
                log_error("Exception executing action directly on server thread: " + e.getMessage());
                LOGGER.error("Exception executing action directly:", e);
            }
        } else {
            server.execute(() -> {
                try {
                    action.accept(server);
                } catch (Exception e) {
                    log_error("Exception during executed server thread action: " + e.getMessage());
                    LOGGER.error("Exception during executed action:", e);
                }
            });
        }
    }

    @HostAccess.Export
    public void log_info(String message) {
        LOGGER.info("[PythonScript] {}", message);
    }

    @HostAccess.Export
    public void log_warning(String message) {
        LOGGER.warn("[PythonScript] {}", message);
    }

    @HostAccess.Export
    public void log_error(String message) {
        LOGGER.error("[PythonScript] {}", message);
    }

    @HostAccess.Export
    public void send_chat(String message) {
        runOnServerThreadExecute(s -> {
            s.getPlayerManager().broadcast(Text.literal(message), false);
        });
    }

    @HostAccess.Export
    public void run_command(String command) {
        runOnServerThreadExecute(s -> {
            LOGGER.info("[PythonScript] Executing command via API: /{} (Source: {})", command, commandSource.getName());
            try {
                s.getCommandManager().executeWithPrefix(commandSource, command);
            } catch (Exception e) {
                log_error("Error executing command '/" + command + "': " + e.getMessage());
                LOGGER.error("Exception executing command '{}':", command, e);
            }
        });
    }

    @HostAccess.Export
    public Map<String, Double> get_player_pos(String playerName) {
        return runOnServerThreadSubmit(s -> {
            ServerPlayerEntity player = s.getPlayerManager().getPlayer(playerName);
            if (player != null) {
                return entityPosToMap(player);
            } else {
                log_error("Player not found: " + playerName);
                return null;
            }
        });
    }

    @HostAccess.Export
    public String get_player_dimension(String playerName) {
        return runOnServerThreadSubmit(s -> {
            ServerPlayerEntity player = s.getPlayerManager().getPlayer(playerName);
            if (player != null && player.getWorld() != null) {
                return player.getWorld().getRegistryKey().getValue().toString();
            } else if (player == null) {
                log_error("Player not found: " + playerName);
            } else {
                log_error("Player " + playerName + " has null world reference.");
            }
            return null;
        });
    }


    @HostAccess.Export
    public boolean teleport_player(String playerName, double x, double y, double z, String dimensionId) {
        return Boolean.TRUE.equals(runOnServerThreadSubmit(s -> {
            ServerPlayerEntity player = s.getPlayerManager().getPlayer(playerName);
            if (player == null) {
                log_error("Player not found for teleport: " + playerName);
                return false;
            }

            Optional<ServerWorld> targetWorldOpt = resolveWorld(s, dimensionId);
            if (targetWorldOpt.isEmpty()) {
                return false;
            }

            try {
                player.setPos(x, y, z);
                log_info("Teleported player " + playerName + " to " + x + "," + y + "," + z + " in " + dimensionId);
                return true;
            } catch (Exception e) {
                log_error(String.format("Error teleporting player %s: %s", playerName, e.getMessage()));
                LOGGER.error("Exception during teleport:", e);
                return false;
            }
        }));
    }

    @HostAccess.Export
    public String get_block(int x, int y, int z, String dimensionId) {
        return runOnServerThreadSubmit(s -> {
            Optional<ServerWorld> worldOpt = resolveWorld(s, dimensionId);
            if (worldOpt.isEmpty()) return null;

            BlockPos pos = new BlockPos(x, y, z);
            ServerWorld world = worldOpt.get();
            if (!world.isChunkLoaded(pos)) {
                log_warning(String.format("Attempted to get block in unloaded chunk at %d,%d,%d in %s", x, y, z, dimensionId));
                return null;
            }

            try {
                Identifier blockId = world.getBlockState(pos).getBlock().getRegistryEntry().registryKey().getValue();
                return blockId.toString();
            } catch (Exception e) {
                log_error(String.format("Error getting block at %d,%d,%d in %s: %s", x, y, z, dimensionId, e.getMessage()));
                LOGGER.error("Exception getting block state:", e);
                return null;
            }
        });
    }

    @HostAccess.Export
    public boolean set_block(int x, int y, int z, String blockIdStr, String dimensionId) {
        return Boolean.TRUE.equals(runOnServerThreadSubmit(s -> {
            Optional<ServerWorld> worldOpt = resolveWorld(s, dimensionId);
            if (worldOpt.isEmpty()) return false;

            Identifier blockIdentifier = Identifier.tryParse(blockIdStr);
            if (blockIdentifier == null) {
                log_error("Invalid block ID format for set_block: " + blockIdStr);
                return false;
            }
            Optional<Block> blockOpt = s.getRegistryManager()
                    .getOptional(RegistryKeys.BLOCK).flatMap(optional -> optional.getOptionalValue(blockIdentifier));

            if (blockOpt.isEmpty()) {
                log_error("Block not found for set_block: " + blockIdStr);
                return false;
            }

            BlockPos pos = new BlockPos(x, y, z);
            ServerWorld world = worldOpt.get();
            if (!world.isChunkLoaded(pos)) {
                log_warning(String.format("Attempted to set block in unloaded chunk at %d,%d,%d in %s", x, y, z, dimensionId));
                return false;
            }

            try {
                boolean success = world.setBlockState(pos, blockOpt.get().getDefaultState(), 3);
                if (!success) {
                    log_warning("setBlockState returned false for " + blockIdStr + " at " + pos + " in " + dimensionId);
                }
                return success;
            } catch (Exception e) {
                log_error(String.format("Error setting block %s at %d,%d,%d in %s: %s", blockIdStr, x, y, z, dimensionId, e.getMessage()));
                LOGGER.error("Exception setting block state:", e);
                return false;
            }
        }));
    }

    @HostAccess.Export
    public String get_executor_name() {
        return commandSource.getName();
    }

    @HostAccess.Export
    public Map<String, Double> get_executor_pos() {
        return runOnServerThreadSubmit(s -> {
            Entity entity = commandSource.getEntity();
            return entityPosToMap(entity);
        });
    }

    @HostAccess.Export
    public String get_executor_dimension() {
        return runOnServerThreadSubmit(s -> {
            Entity entity = commandSource.getEntity();
            if (entity != null && entity.getWorld() != null) {
                return entity.getWorld().getRegistryKey().getValue().toString();
            }
            return null;
        });
    }

    private Map<String, Double> entityPosToMap(Entity entity) {
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

    private Optional<ServerWorld> resolveWorld(MinecraftServer s, String dimensionId) {
        if (s == null || dimensionId == null) return Optional.empty();

        Identifier dimIdentifier = Identifier.tryParse(dimensionId);
        if (dimIdentifier == null) {
            log_error("Invalid dimension ID format: " + dimensionId);
            return Optional.empty();
        }
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimIdentifier);
        ServerWorld world = s.getWorld(worldKey);
        if (world == null) {
            log_error("Dimension not found or not loaded: " + dimensionId);
            return Optional.empty();
        }
        return Optional.of(world);
    }
}