package minhcrafters.pyfabric;

import minhcrafters.pyfabric.command.Command;
import minhcrafters.pyfabric.python.PythonInterpreter;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnakesAndThreads implements ModInitializer {
    public static final String MOD_ID = "pyfabric";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static PythonInterpreter pythonInterpreter;
    private static MinecraftServer minecraftServer = null;

    @Override
    public void onInitialize() {
        pythonInterpreter = new PythonInterpreter();

        LOGGER.info("Hello Fabric world!");

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, shutting down Python...");
            if (pythonInterpreter != null) {
                pythonInterpreter.close();
            }
            minecraftServer = null;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            minecraftServer = server;
            pythonInterpreter.init();
            LOGGER.info("Server started, MinecraftServer instance captured.");
        });

        CommandRegistrationCallback.EVENT.register(Command::register);
    }

    public static MinecraftServer getMinecraftServer() {
        return minecraftServer;
    }

    public static void setMinecraftServer(MinecraftServer server) {
        minecraftServer = server;
    }

    public static PythonInterpreter getPythonInterpreter() {
        return pythonInterpreter;
    }
}