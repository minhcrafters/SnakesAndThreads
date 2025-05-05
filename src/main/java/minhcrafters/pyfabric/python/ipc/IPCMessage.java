package minhcrafters.pyfabric.python.ipc;

import java.util.Map;

public class IPCMessage {

    public static final String ID_MARKER = "PYFABRIC_ID::";

    public enum MessageType {
        REQUEST, RESPONSE, LOG, SCRIPT_OUTPUT, ERROR, HANDSHAKE
    }

    public MessageType type;
    public String id;
    public String action;
    public Map<String, Object> args;
    public boolean success;
    public Object result;
    public String error;
    public String level;
    public String message;
    public String stream;
    public String content;

    public static IPCMessage createRequest(String id, String action, Map<String, Object> args) {
        IPCMessage msg = new IPCMessage();
        msg.type = MessageType.REQUEST;
        msg.id = id;
        msg.action = action;
        msg.args = args;
        return msg;
    }

    public static IPCMessage createSuccessResponse(String id, Object result) {
        IPCMessage msg = new IPCMessage();
        msg.type = MessageType.RESPONSE;
        msg.id = id;
        msg.success = true;
        msg.result = result;
        return msg;
    }

    public static IPCMessage createErrorResponse(String id, String errorMessage) {
        IPCMessage msg = new IPCMessage();
        msg.type = MessageType.RESPONSE;
        msg.id = id;
        msg.success = false;
        msg.error = errorMessage;
        return msg;
    }

    public static IPCMessage createLog(String level, String message) {
        IPCMessage msg = new IPCMessage();
        msg.type = MessageType.LOG;
        msg.level = level;
        msg.message = message;
        return msg;
    }

    public static IPCMessage createScriptOutput(String stream, String content) {
        IPCMessage msg = new IPCMessage();
        msg.type = MessageType.SCRIPT_OUTPUT;
        msg.stream = stream;
        msg.content = content;
        return msg;
    }

    public static IPCMessage createError(String errorMessage) {
        IPCMessage msg = new IPCMessage();
        msg.type = MessageType.ERROR;
        msg.error = errorMessage;
        return msg;
    }
}
