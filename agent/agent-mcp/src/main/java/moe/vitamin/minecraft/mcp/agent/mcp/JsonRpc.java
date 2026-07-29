package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * The JSON-RPC 2.0 envelope MCP rides on.
 *
 * <p>Only the pieces MCP actually uses are modelled. Batching is absent on purpose: the
 * 2025-06-18 revision removed it, and accepting batches would mean supporting a framing the
 * protocol no longer defines.
 */
final class JsonRpc {

    static final String VERSION = "2.0";

    // Standard JSON-RPC codes.
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {}

    /**
     * One decoded request.
     *
     * @param id     request id, or {@code null} for a notification, which takes no response
     * @param method the method name
     * @param params the params object, never {@code null} (an empty object stands in)
     */
    record Request(JsonNode id, String method, JsonNode params) {

        boolean isNotification() {
            return id == null || id.isNull();
        }
    }

    static ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", VERSION);
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", VERSION);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);

        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
