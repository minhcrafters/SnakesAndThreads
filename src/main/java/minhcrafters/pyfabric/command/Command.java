package minhcrafters.pyfabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import minhcrafters.pyfabric.SnakesAndThreads;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.*;

public class Command {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("pyexec")
                .requires(source -> source.hasPermissionLevel(2))
                .then(argument("script_file", StringArgumentType.greedyString())
                        .executes(context -> executeScriptFile(context.getSource(), StringArgumentType.getString(context, "script_file"))))
        );
    }

    private static int executeScriptFile(ServerCommandSource source, String fileName) {
        // Execute the script - the returned future completes when connection is established or fails
        SnakesAndThreads.getPythonInterpreter()
                .executeScriptFile(fileName, source, source.getServer())
                .exceptionally(ex -> {
                    // This catches errors during the launch/connection phase
                    source.sendError(Text.literal("Script launch/connection error: " + ex.getMessage()));
                    SnakesAndThreads.LOGGER.error("Error launching/connecting script file {}", fileName, ex);
                    return null;
                });

        // Command returns immediately; feedback/errors happen asynchronously via socket/logs
        return 1;
    }
}
