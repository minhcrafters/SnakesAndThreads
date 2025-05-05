package minhcrafters.pyfabric.python.ipc;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import minhcrafters.pyfabric.api.MinecraftAPI;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public class ProcessHandler implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessHandler.class);

    private final Socket socket;
    private final Process process;
    private final String processId;
    private final ServerCommandSource source;
    private final MinecraftServer server;
    private final Consumer<String> disconnectCallback;
    private final MinecraftAPI apiHandler;
    private final Gson gson;
    private PrintWriter writer;
    private volatile boolean running = true;
    private final ConcurrentMap<String, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();


    public ProcessHandler(Socket socket, Process process, String processId, ServerCommandSource source, MinecraftServer server,
                                Consumer<String> disconnectCallback, MinecraftAPI apiHandler, Gson gson) {
        this.socket = socket;
        this.process = process;
        this.processId = processId;
        this.source = source;
        this.server = server;
        this.disconnectCallback = disconnectCallback;
        this.apiHandler = apiHandler;
        this.gson = gson;
    }

    public Process getProcess() {
        return process;
    }

    @Override
    public void run() {
        String threadName = Thread.currentThread().getName();
        LOGGER.info("Starting handler for Python process {} on thread {}", processId, threadName);
        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            this.writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            String line;
            while (running && (line = reader.readLine()) != null) {
                LOGGER.trace("[{}] Received: {}", processId.substring(0, 6), line);
                try {
                    IPCMessage message = gson.fromJson(line, IPCMessage.class);
                    if (message != null && message.type != null) {
                        handleIncomingMessage(message);
                    } else {
                        LOGGER.warn("[{}] Received invalid message (null or no type): {}", processId.substring(0, 6), line);
                    }
                } catch (JsonSyntaxException e) {
                    LOGGER.error("[{}] JSON Syntax Error processing line: {}", processId.substring(0, 6), line, e);
                    sendError("JSON parsing error on server: " + e.getMessage());
                } catch (Exception e) {
                    LOGGER.error("[{}] Unexpected error handling message: {}", processId.substring(0, 6), line, e);
                    sendError("Internal server error handling message: " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            if (running) {
                LOGGER.warn("[{}] SocketException (likely disconnect): {}", processId.substring(0, 6), e.getMessage());
            } else {
                LOGGER.info("[{}] Socket closed during shutdown.", processId.substring(0, 6));
            }
        } catch (IOException e) {
            if (running) {
                LOGGER.error("[{}] IOException in handler loop: {}", processId.substring(0, 6), e.getMessage(), e);
            }
        } catch (Exception e) {
            LOGGER.error("[{}] Unexpected exception in handler run method: {}", processId.substring(0, 6), e.getMessage(), e);
        } finally {
            LOGGER.info("Handler loop finished for {}. Cleaning up.", processId);
            shutdown();
        }
    }

    private void handleIncomingMessage(IPCMessage message) {
        switch (message.type) {
            case REQUEST:
                handleApiRequest(message);
                break;
            case LOG:
                handleLogMessage(message);
                break;
            case SCRIPT_OUTPUT:
                handleScriptOutput(message);
                break;
            default:
                LOGGER.warn("[{}] Received unhandled message type: {}", processId.substring(0, 6), message.type);
        }
    }

    private void handleLogMessage(IPCMessage message) {
        String logMsg = String.format("[Python-%s] %s", processId.substring(0, 6), message.message);
        switch (message.level.toLowerCase()) {
            case "warning":
            case "warn":
                LOGGER.warn(logMsg);
                break;
            case "error":
            case "critical":
                LOGGER.error(logMsg);
                break;
            case "debug":
                LOGGER.debug(logMsg);
                break;
            case "info":
            default:
                LOGGER.info(logMsg);
                break;
        }
    }

    private void handleScriptOutput(IPCMessage message) {
        String logMsg = String.format("[PyOut-%s/%s] %s", processId.substring(0, 6), message.stream, message.content);
        if ("stderr".equalsIgnoreCase(message.stream)) {
            LOGGER.warn(logMsg);
        } else {
            LOGGER.info(logMsg);
        }
    }


    private void handleApiRequest(IPCMessage request) {
        if (request.id == null || request.action == null) {
            LOGGER.error("[{}] Received invalid API request (missing id or action): {}", processId.substring(0, 6), gson.toJson(request));
            sendError("Invalid API request received by server (missing id or action).");
            return;
        }

        LOGGER.debug("[{}] Handling API request {}: {}", processId.substring(0, 6), request.id, request.action);
        CompletableFuture<Object> futureResult = apiHandler.handleApiCall(request.action, request.args, source, server);
        futureResult.whenCompleteAsync((result, throwable) -> {
            if (!running) return;

            IPCMessage response;
            if (throwable != null) {
                LOGGER.error("[{}] Error executing API action '{}' for request {}: {}",
                        processId.substring(0, 6), request.action, request.id, throwable.getMessage(), throwable);
                response = IPCMessage.createErrorResponse(request.id, throwable.getMessage());
            } else {
                LOGGER.debug("[{}] Sending success response for request {}: {}", processId.substring(0, 6), request.id, result);
                response = IPCMessage.createSuccessResponse(request.id, result);
            }
            sendMessage(response);
        }, server);
    }

    private synchronized void sendMessage(IPCMessage message) {
        if (!running || writer == null || socket.isClosed()) {
            LOGGER.warn("[{}] Cannot send message, handler not running or writer/socket closed.", processId.substring(0, 6));
            return;
        }
        try {
            String json = gson.toJson(message);
            LOGGER.trace("[{}] Sending: {}", processId.substring(0, 6), json);
            writer.println(json);
            if (writer.checkError()) {
                LOGGER.error("[{}] PrintWriter encountered an error sending message.", processId.substring(0, 6));
                shutdown();
            }
        } catch (Exception e) {
            LOGGER.error("[{}] Exception sending message: {}", processId.substring(0, 6), e.getMessage(), e);
            shutdown();
        }
    }

    private void sendError(String errorMessage) {
        sendMessage(IPCMessage.createError(errorMessage));
    }


    public void notifyProcessExited(int exitCode) {
        LOGGER.debug("Handler notified that process {} exited with code {}", processId, exitCode);
        if (exitCode != 0) {
            sendError("Script process exited abnormally with code: " + exitCode);
            server.execute(() -> source.sendError(Text.literal("Script exited abnormally (code " + exitCode + ").")));
        } else {
            server.execute(() -> source.sendFeedback(() -> Text.literal("Script finished.").formatted(Formatting.GREEN), false));
        }
        shutdown();
    }


    public synchronized void shutdown() {
        if (!running) return;
        running = false;
        LOGGER.info("Shutting down handler for process {}", processId);
        try {
            if (socket != null && !socket.isClosed()) {
                LOGGER.debug("[{}] Closing socket...", processId.substring(0, 6));
                socket.close();
            }
        } catch (IOException e) {
            LOGGER.warn("[{}] Error closing socket: {}", processId.substring(0, 6), e.getMessage());
        }
        if (!pendingRequests.isEmpty()) {
            LOGGER.warn("[{}] {} pending requests outstanding during shutdown.", processId.substring(0, 6), pendingRequests.size());
            pendingRequests.clear();
        }
        if (process != null && process.isAlive()) {
            LOGGER.warn("[{}] Forcing termination of process {} during handler shutdown.", processId.substring(0, 6), process.pid());
            process.destroyForcibly();
        }
        disconnectCallback.accept(processId);

        LOGGER.info("Handler for {} shut down.", processId);
    }
}
