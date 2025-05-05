package minhcrafters.pyfabric.python;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import minhcrafters.pyfabric.SnakesAndThreads;
import minhcrafters.pyfabric.api.MinecraftAPI;
import minhcrafters.pyfabric.python.ipc.IPCMessage;
import minhcrafters.pyfabric.python.ipc.ProcessHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PythonInterpreter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonInterpreter.class);
    private static final Path SCRIPT_DIR = Paths.get(FabricLoader.getInstance().getConfigDir().toString(), SnakesAndThreads.MOD_ID, "scripts");
    private static final int SERVER_PORT = 49152;
    private static final String PYTHON_COMMAND = "python";

    private final ExecutorService processExecutor = Executors.newCachedThreadPool(createThreadFactory("PyFabric-ProcessRunner-%d"));
    private final ExecutorService socketHandlerExecutor = Executors.newCachedThreadPool(createThreadFactory("PyFabric-SocketHandler-%d"));
    private final ConcurrentMap<String, ProcessHandler> activeProcesses = new ConcurrentHashMap<>();
    private final MinecraftAPI apiHandler = new MinecraftAPI();
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final Gson gson = new GsonBuilder().serializeNulls().create();


    public PythonInterpreter() {
        initializeScriptDir();
    }

    public void startServerSocket() {
        if (running) {
            LOGGER.warn("Server socket already running.");
            return;
        }
        try {
            serverSocket = new ServerSocket(SERVER_PORT, 50, InetAddress.getLoopbackAddress());
            running = true;
            LOGGER.info("Python IPC ServerSocket listening on {}", serverSocket.getLocalSocketAddress());
            Thread acceptorThread = new Thread(this::acceptConnections, "PyFabric-SocketAcceptor");
            acceptorThread.setDaemon(true);
            acceptorThread.start();

        } catch (IOException e) {
            LOGGER.error("FATAL: Could not start Python IPC ServerSocket on port {}. Python scripting will likely fail.", SERVER_PORT, e);
            serverSocket = null;
            running = false;
        }
    }

    private void acceptConnections() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                LOGGER.debug("Incoming connection from {}, delaying handling until token received.", clientSocket.getRemoteSocketAddress());
            } catch (SocketException e) {
                if (running) {
                    LOGGER.error("SocketException on acceptor thread (might be server stopping): {}", e.getMessage());
                } else {
                    LOGGER.info("ServerSocket closed, acceptor thread stopping.");
                }
                running = false;
            } catch (IOException e) {
                LOGGER.error("IOException on acceptor thread", e);
            }
        }
        LOGGER.info("Socket acceptor thread finished.");
    }


    private void initializeScriptDir() {
    }

    public CompletableFuture<Void> executeScriptFile(String fileName, ServerCommandSource source, MinecraftServer server) {
        if (!running || serverSocket == null) {
            source.sendError(Text.literal("Python Executor IPC server is not running. Check logs."));
            return CompletableFuture.failedFuture(new IllegalStateException("IPC server not running"));
        }

        Path scriptPath = SCRIPT_DIR.resolve(fileName).toAbsolutePath();
        if (Files.notExists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            source.sendError(Text.literal("Script file not found: " + fileName));
            return CompletableFuture.completedFuture(null);
        }
        String processId = UUID.randomUUID().toString();
        CompletableFuture<ProcessHandler> handlerFuture = new CompletableFuture<>();

        activeProcesses.put(processId, null);
        processExecutor.submit(() -> {
            try {
                Process process = launchPythonProcess(scriptPath, processId, source, server);
                LOGGER.info("Launched Python script '{}' with PID {} (Internal ID: {})", fileName, process.pid(), processId);
                Socket clientSocket = waitForConnection(processId);

                if (clientSocket != null) {
                    LOGGER.info("Python script process {} connected from {}", processId, clientSocket.getRemoteSocketAddress());
                    ProcessHandler handler = new ProcessHandler(clientSocket, process, processId, source, server, this::onProcessDisconnect, apiHandler, gson);
                    activeProcesses.put(processId, handler);
                    socketHandlerExecutor.submit(handler);
                    handlerFuture.complete(handler);
                } else {
                    LOGGER.error("Python script process {} did not connect back within the timeout.", processId);
                    process.destroyForcibly();
                    activeProcesses.remove(processId);
                    handlerFuture.completeExceptionally(new TimeoutException("Python script did not connect back"));
                    server.execute(() -> source.sendError(Text.literal("Script failed to establish communication.")));
                }
                monitorProcessExit(process, processId, source);

            } catch (Exception e) {
                LOGGER.error("Failed to launch or connect to Python script process {}: {}", processId, fileName, e);
                activeProcesses.remove(processId);
                handlerFuture.completeExceptionally(e);
                server.execute(() -> source.sendError(Text.literal("Failed to start script: " + e.getMessage())));
            }
        });
        return handlerFuture.thenAccept(handler -> {
            LOGGER.debug("Python process handler is ready for {}", processId);
            source.sendFeedback(() -> Text.literal("Executing script: " + fileName).formatted(Formatting.GRAY), false);
        }).exceptionally(e -> {
            LOGGER.error("Failed to establish connection with script {}", fileName, e);
            return null;
        });
    }

    private Process launchPythonProcess(Path scriptPath, String processId, ServerCommandSource source, MinecraftServer server) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(PYTHON_COMMAND);
        command.add(scriptPath.toString());
        command.add("--pyfabric-port=" + serverSocket.getLocalPort());
        command.add("--pyfabric-id=" + processId);
        command.add("--executor-name=" + source.getName());
        if (source.getEntity() != null) {
            command.add("--executor-pos=" + source.getPosition().x + "," + source.getPosition().y + "," + source.getPosition().z);
            command.add("--executor-dim=" + source.getWorld().getRegistryKey().getValue().toString());
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(SCRIPT_DIR.toFile());
        pb.redirectErrorStream(true);
        LOGGER.info("Launching Python: {}", String.join(" ", pb.command()));
        Process process = pb.start();
        captureInitialOutput(process, processId);

        return process;
    }

    private void captureInitialOutput(Process process, String processId) {
        Thread initialOutputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (activeProcesses.containsKey(processId) && activeProcesses.get(processId) != null || !process.isAlive()) {
                        LOGGER.debug("Initial output capture stopped for {}", processId);
                        break;
                    }
                    LOGGER.info("[Python-{}-init] {}", processId.substring(0, 6), line);
                }
            } catch (IOException e) {
                if (!e.getMessage().contains("Stream closed")) {
                    LOGGER.warn("Error reading initial output for Python process {}: {}", processId, e.getMessage());
                }
            } finally {
                LOGGER.debug("Initial output reader finished for {}", processId);
            }
        }, "PyFabric-InitialOutput-" + processId.substring(0, 6));
        initialOutputThread.setDaemon(true);
        initialOutputThread.start();
    }

    private Socket waitForConnection(String expectedProcessId) throws IOException {
        long deadline = System.currentTimeMillis() + 10 * 1000L;
        while (System.currentTimeMillis() < deadline) {
            serverSocket.setSoTimeout(1000);
            Socket clientSocket = null;
            try {
                clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(5000);
                LOGGER.debug("Accepted connection from {}, checking ID...", clientSocket.getRemoteSocketAddress());
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                String receivedIdLine = reader.readLine();

                if (receivedIdLine != null && receivedIdLine.startsWith(IPCMessage.ID_MARKER)) {
                    String receivedId = receivedIdLine.substring(IPCMessage.ID_MARKER.length());
                    if (expectedProcessId.equals(receivedId)) {
                        LOGGER.debug("Correct process ID ({}) received from {}", receivedId, clientSocket.getRemoteSocketAddress());
                        clientSocket.setSoTimeout(0);
                        serverSocket.setSoTimeout(0);
                        return clientSocket;
                    } else {
                        LOGGER.warn("Received connection with incorrect ID '{}', expected '{}'. Closing.", receivedId, expectedProcessId);
                    }
                } else {
                    LOGGER.warn("Received invalid handshake from {}. Line: '{}'. Closing.", clientSocket.getRemoteSocketAddress(), receivedIdLine);
                }
                closeSocket(clientSocket);

            } catch (SocketTimeoutException e) {
                LOGGER.trace("Accept timed out, continuing wait for {}", expectedProcessId);
                closeSocket(clientSocket);
            } catch (IOException e) {
                LOGGER.error("IOException during connection wait for {}", expectedProcessId, e);
                closeSocket(clientSocket);
                throw e;
            }
        }
        serverSocket.setSoTimeout(0);
        return null;
    }


    private void monitorProcessExit(Process process, String processId, ServerCommandSource source) {
        processExecutor.submit(() -> {
            try {
                int exitCode = process.waitFor();
                LOGGER.info("Python process {} (PID {}) exited with code {}", processId, process.pid(), exitCode);
                ProcessHandler handler = activeProcesses.get(processId);
                if (handler != null) {
                    handler.notifyProcessExited(exitCode);
                } else {
                    onProcessDisconnect(processId);
                }
                if (exitCode != 0) {
                    if (handler == null) {
                        MinecraftServer server = source.getServer();
                        if (server != null) {
                            server.execute(() -> source.sendError(Text.literal("Script exited abnormally (code " + exitCode + ").")));
                        }
                    }
                }
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for process {} exit.", processId);
                Thread.currentThread().interrupt();
            }
        });
    }

    protected void onProcessDisconnect(String processId) {
        ProcessHandler removed = activeProcesses.remove(processId);
        if (removed != null) {
            LOGGER.info("Removed handler for disconnected process {}", processId);
            if (removed.getProcess().isAlive()) {
                LOGGER.warn("Process {} still alive after socket disconnection, attempting to destroy.", processId);
                removed.getProcess().destroy();
                try {
                    if (!removed.getProcess().waitFor(2, TimeUnit.SECONDS)) {
                        LOGGER.warn("Force destroying process {}", processId);
                        removed.getProcess().destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void closeSocket(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
            }
        }
    }


    public void shutdown() {
        LOGGER.info("Shutting down Python executor...");
        running = false;

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                LOGGER.info("ServerSocket closed.");
            } catch (IOException e) {
                LOGGER.error("Error closing server socket", e);
            }
        }
        serverSocket = null;
        LOGGER.info("Terminating {} active Python processes...", activeProcesses.size());
        List<ProcessHandler> handlers = new ArrayList<>(activeProcesses.values());
        activeProcesses.clear();

        for (ProcessHandler handler : handlers) {
            if (handler != null) {
                handler.shutdown();
            }
        }
        shutdownExecutor(socketHandlerExecutor, "SocketHandler");
        shutdownExecutor(processExecutor, "ProcessRunner");

        LOGGER.info("Python executor shutdown complete.");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                LOGGER.warn("{} executor service did not terminate gracefully.", name);
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.error("{} executor service did not terminate.", name);
                }
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory createThreadFactory(String nameFormat) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, String.format(nameFormat, counter.incrementAndGet()));
                t.setDaemon(true);
                return t;
            }
        };
    }

    public void start(MinecraftServer server) {
        startServerSocket();
    }
}