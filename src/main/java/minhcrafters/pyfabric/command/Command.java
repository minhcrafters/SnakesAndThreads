package minhcrafters.pyfabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import minhcrafters.pyfabric.SnakesAndThreads;
import minhcrafters.pyfabric.python.PythonInterpreter;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static net.minecraft.server.command.CommandManager.*;

public class Command {
    private static final SuggestionProvider<ServerCommandSource> SCRIPT_FILE_SUGGESTIONS = (context, builder) -> {
        Path scriptDir = Paths.get("config", SnakesAndThreads.MOD_ID, "scripts");
        if (!Files.isDirectory(scriptDir) || !Files.isReadable(scriptDir)) {
            if (!Files.exists(scriptDir)) {
                SnakesAndThreads.LOGGER.trace("Script directory for suggestions does not exist: {}", scriptDir);
            } else {
                SnakesAndThreads.LOGGER.warn("Cannot read script directory for suggestions: {}", scriptDir);
            }
            return Suggestions.empty();
        }

        String remaining = builder.getRemaining().toLowerCase();

        try (Stream<Path> stream = Files.list(scriptDir)) {
            stream
                    .filter(path -> !Files.isDirectory(path))
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.toLowerCase().endsWith(".py"))
                    .filter(fileName -> fileName.toLowerCase().startsWith(remaining))
                    .forEach(builder::suggest);
        } catch (IOException e) {
            SnakesAndThreads.LOGGER.error("Failed to list script files for command suggestions in {}: {}", scriptDir, e.getMessage());
            return Suggestions.empty();
        }

        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("pyexec")
                .requires(source -> source.hasPermissionLevel(2))
                .then(argument("script_file", StringArgumentType.greedyString())
                        .suggests(SCRIPT_FILE_SUGGESTIONS)
                        .executes(context -> executeScriptFile(context.getSource(), StringArgumentType.getString(context, "script_file"))))
        );

        dispatcher.register(literal("pyeval")
                .requires(source -> source.hasPermissionLevel(4))
                .then(argument("python_code", StringArgumentType.greedyString())
                        .executes(context -> executeScriptCode(context.getSource(), StringArgumentType.getString(context, "python_code"))))
        );
    }

    private static int executeScriptFile(ServerCommandSource source, String fileName) {
        PythonInterpreter executor = SnakesAndThreads.getPythonInterpreter();
        if (executor == null) {
            source.sendError(Text.literal("Python Executor is not available (initialization failed?). Check logs."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendError(Text.literal("Server instance is not available (unexpected state)."));
            return 0;
        }
        executor.executeScriptFile(fileName, source, server)
                .exceptionally(ex -> {
                    SnakesAndThreads.LOGGER.error("Error submitting script file task '{}': {}", fileName, ex.getMessage(), ex);
                    source.sendError(Text.literal("Failed to start script task: " + ex.getMessage()));
                    return null;
                });
        return 1;
    }

    private static int executeScriptCode(ServerCommandSource source, String code) {
        PythonInterpreter executor = SnakesAndThreads.getPythonInterpreter();
        if (executor == null) {
            source.sendError(Text.literal("Python interpreter is not available (initialization failed?). Check logs."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendError(Text.literal("Server instance is not available (unexpected state)."));
            return 0;
        }
        executor.executeScript(code, source, server, "<eval>")
                .exceptionally(ex -> {
                    SnakesAndThreads.LOGGER.error("Error submitting eval task: {}", ex.getMessage(), ex);
                    source.sendError(Text.literal("Failed to start eval task: " + ex.getMessage()));
                    return null;
                });
        return 1;
    }
}