package minhcrafters.pyfabric.python;

import minhcrafters.pyfabric.SnakesAndThreads;
import minhcrafters.pyfabric.api.MinecraftAPI;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.graalvm.polyglot.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PythonInterpreter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonInterpreter.class);
    private Context polyglotContext;
    private ExecutorService scriptExecutorService;

    private static final Path SCRIPT_DIR = Paths.get("config", SnakesAndThreads.MOD_ID, "scripts");

    public PythonInterpreter() {
    }

    public void init() {
        try {
            scriptExecutorService = Executors.newSingleThreadExecutor(createThreadFactory());

            LOGGER.info("Creating GraalVM Python context...");

            Context.Builder builder = Context.newBuilder("python")
                    .allowExperimentalOptions(true)
                    .option("python.ForceImportSite", "true")
                    .option("engine.WarnInterpreterOnly", "false")
                    .option("python.EmulateJython", "true")
                    .allowAllAccess(true);

            polyglotContext = builder.build();
            LOGGER.info("GraalVM Python context created successfully.");
//            try {
//                polyglotContext.eval("python", "import sys, os; print(f'Python {sys.version} initialized within Java. CWD: {os.getcwd()}')");
//            } catch (PolyglotException testEx) {
//                LOGGER.warn("Post-initialization Python test failed: {}", testEx.getMessage());
//            }

            initializeScriptDir();

        } catch (Exception e) {
            LOGGER.error("FATAL: Failed to initialize bundled GraalVM Python context! Python scripting will be disabled.", e);
            polyglotContext = null;
        }
    }

    private void initializeScriptDir() {
        try {
            if (Files.notExists(SCRIPT_DIR)) {
                Files.createDirectories(SCRIPT_DIR);
                LOGGER.info("Created script directory: {}", SCRIPT_DIR.toAbsolutePath());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create script directory: {}", SCRIPT_DIR, e);
        }
    }

    public CompletableFuture<Void> executeScriptFile(String fileName, ServerCommandSource source, MinecraftServer server) {
        if (polyglotContext == null) {
            source.sendError(Text.literal("Python execution context is not available. Check server logs."));
            return CompletableFuture.failedFuture(new IllegalStateException("Python context not available"));
        }

        Path scriptPath = SCRIPT_DIR.resolve(fileName).toAbsolutePath();
        if (!scriptPath.startsWith(SCRIPT_DIR.toAbsolutePath())) {
            String errorMsg = "Script path is outside the allowed directory (sandbox violation attempt).";
            source.sendError(Text.literal(errorMsg));
            return CompletableFuture.failedFuture(new SecurityException(errorMsg));
        }
        if (Files.notExists(scriptPath)) {
            String errorMsg = "Script file not found: " + fileName;
            source.sendError(Text.literal(errorMsg));
            return CompletableFuture.failedFuture(new FileNotFoundException(errorMsg));
        }
        if (!Files.isRegularFile(scriptPath)) {
            String errorMsg = "Target is not a regular file: " + fileName;
            source.sendError(Text.literal(errorMsg));
            return CompletableFuture.failedFuture(new IllegalArgumentException(errorMsg));
        }
        if (!Files.isReadable(scriptPath)) {
            String errorMsg = "Script file is not readable: " + fileName;
            source.sendError(Text.literal(errorMsg));
            return CompletableFuture.failedFuture(new IOException(errorMsg));
        }


        try {
            String scriptContent = Files.readString(scriptPath);
            source.sendFeedback(() -> Text.literal("Executing script: " + fileName).formatted(Formatting.GRAY), false);
            return executeScript(scriptContent, source, server, scriptPath.toString());
        } catch (IOException e) {
            source.sendError(Text.literal("Failed to read script file " + fileName + ": " + e.getMessage()));
            LOGGER.error("Error reading script {}", scriptPath, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> executeScript(String scriptContent, ServerCommandSource source, MinecraftServer server, String scriptName) {
        if (polyglotContext == null) {
            source.sendError(Text.literal("Python execution context is not available. Check server logs."));
            return CompletableFuture.failedFuture(new IllegalStateException("Python context not available"));
        }
        return CompletableFuture.runAsync(() -> {
            long startTime = System.nanoTime();
            try {
                polyglotContext.enter();
                MinecraftAPI mcApi = new MinecraftAPI(server, source);
                Value bindings = polyglotContext.getBindings("python");
                bindings.putMember("mc", mcApi);
                bindings.putMember("__script_name__", scriptName);

                LOGGER.info("Executing Python script '{}' for {}", scriptName, source.getName());
                Source polyglotSource = Source.newBuilder("python", scriptContent, scriptName).build();
                Value result = polyglotContext.eval(polyglotSource);

                long durationMillis = (System.nanoTime() - startTime) / 1_000_000;
                LOGGER.info("Script '{}' executed successfully in {} ms.", scriptName, durationMillis);
                handleResult(result, source);
            } catch (PolyglotException e) {
                long durationMillis = (System.nanoTime() - startTime) / 1_000_000;
                LOGGER.error("Error executing Python script '{}' for {} after {} ms: {}", scriptName, source.getName(), durationMillis, e.getMessage(), e);
                handlePolyglotError(e, source);
            } catch (Exception e) {
                long durationMillis = (System.nanoTime() - startTime) / 1_000_000;
                LOGGER.error("Unexpected error during Python script execution '{}' after {} ms: {}", scriptName, durationMillis, e.getMessage(), e);
                source.sendError(Text.literal("Internal error during script execution: " + e.getClass().getSimpleName()).formatted(Formatting.RED));
            } finally {
                polyglotContext.leave();
            }
        }, scriptExecutorService).exceptionally(e -> {
            LOGGER.error("Failed to execute script task '{}': {}", scriptName, e.getMessage(), e);
            source.sendError(Text.literal("Failed to run script task: " + e.getMessage()));
            return null;
        });
    }

    private void handleResult(Value result, ServerCommandSource source) {
        if (result != null && !result.isNull()) {
            String resultPrefix = "Script result: ";
            Formatting format = Formatting.GREEN;
            String resultStr = result.toString();

            try {
                if (result.isString() || result.isNumber() || result.isBoolean()) {
                } else if (result.canExecute()) {
                    resultPrefix = "Script finished.";
                    resultStr = "(result is callable)";
                    format = Formatting.GRAY;
                } else if (result.hasMembers()) {
                    resultPrefix = "Script finished.";
                    resultStr = "(result is object/dict)";
                    format = Formatting.GRAY;
                } else {
                    resultPrefix = "Script finished.";
                    format = Formatting.GRAY;
                }
                if (resultStr.length() > 100) {
                    resultStr = resultStr.substring(0, 97) + "...";
                }
                final String finalPrefix = resultPrefix;
                final String finalResultStr = resultStr;
                final Formatting finalFormat = format;
                source.sendFeedback(() -> Text.literal(finalPrefix + " ").formatted(finalFormat).append(Text.literal(finalResultStr).formatted(finalFormat)), false);

            } catch (PolyglotException e) {
                LOGGER.warn("Error converting script result to string: {}", e.getMessage());
                source.sendFeedback(() -> Text.literal("Script finished (result inspection failed).").formatted(Formatting.YELLOW), false);
            }
        } else {
            source.sendFeedback(() -> Text.literal("Script finished.").formatted(Formatting.GRAY), false);
        }
    }

    private void handlePolyglotError(PolyglotException e, ServerCommandSource source) {
        String simpleMessage;
        String detailedMessage = e.getMessage();

        if (e.isHostException()) {
            Throwable hostEx = e.asHostException();
            simpleMessage = "Java Error: " + hostEx.getClass().getSimpleName();
            detailedMessage = "HostException executing script: " + hostEx;
            LOGGER.error("Host Exception Trace:", hostEx);
        } else if (e.isGuestException()) {
            simpleMessage = "Python Error: " + getPythonExceptionType(e);
        } else if (e.isSyntaxError()) {
            simpleMessage = "Python Syntax Error";
        } else if (e.isCancelled()) {
            simpleMessage = "Script execution cancelled.";
            LOGGER.warn("Script execution was cancelled: {}", e.getMessage());
        } else if (e.isInternalError()) {
            simpleMessage = "Internal Polyglot Error.";
            LOGGER.error("Internal Polyglot Error: {}", e.getMessage(), e);
        } else {
            simpleMessage = "Execution Error";
        }
        SourceSection location = e.getSourceLocation();
        if (location != null && location.isAvailable()) {
            simpleMessage += String.format(" at %s:%d", location.getSource().getName(), location.getStartLine());
        } else {
            String firstLine = detailedMessage.split("\n", 2)[0];
            if (firstLine.length() < 50) {
                simpleMessage += ": " + firstLine;
            }
        }
        if (simpleMessage.length() > 150) {
            simpleMessage = simpleMessage.substring(0, 147) + "...";
        }

        source.sendError(Text.literal(simpleMessage).formatted(Formatting.RED));
        LOGGER.error("Detailed PolyglotException Info: {}", detailedMessage, e);
    }

    private String getPythonExceptionType(PolyglotException e) {
        try {
            Value guestObject = e.getGuestObject();
            if (guestObject != null && !guestObject.isNull() && guestObject.hasMember("__class__")) {
                Value pyClass = guestObject.getMember("__class__");
                if (pyClass != null && pyClass.hasMember("__name__")) {
                    return pyClass.getMember("__name__").asString();
                }
            }
        } catch (Exception ignored) {
            LOGGER.trace("Could not inspect guest exception object details.", ignored);
        }
        return e.getMessage().split("\n", 2)[0];
    }


    public void close() {
        LOGGER.info("Shutting down Python executor service...");
        scriptExecutorService.shutdown();
        if (polyglotContext != null) {
            try {
                polyglotContext.close(true);
                LOGGER.info("GraalVM Python context closed.");
            } catch (Exception e) {
                LOGGER.error("Error closing GraalVM context", e);
            } finally {
                polyglotContext = null;
            }
        }
        try {
            if (!scriptExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Script executor service did not terminate gracefully, forcing shutdown.");
                scriptExecutorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            scriptExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory createThreadFactory() {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(@NotNull Runnable r) {
                Thread t = new Thread(r, String.format("PyFabric-ScriptExecutor-%d", counter.incrementAndGet()));
                t.setDaemon(true);
                return t;
            }
        };
    }
}