package com.pranav.collab_editor.dto;

/**
 * Data Transfer Object (DTO) for a CRDT operation sent over WebSocket.
 *
 * This is the exact JSON shape that travels over the wire between the browser
 * and the Spring Boot server in both directions:
 *   - Browser → Server: client sends this to /app/document/{docId}/op
 *   - Server → Browser: server broadcasts this to /topic/document/{docId}
 *
 * Example INSERT payload (JSON):
 * {
 *   "type":         "INSERT",
 *   "nodeId":       "userA:42",
 *   "leftId":       "userA:41",
 *   "charValue":    "H",
 *   "clientId":     "userA",
 *   "lamportClock": 42
 * }
 *
 * Example DELETE payload (JSON):
 * {
 *   "type":         "DELETE",
 *   "nodeId":       "userA:42",
 *   "leftId":       null,
 *   "charValue":    null,
 *   "clientId":     "userA",
 *   "lamportClock": 43
 * }
 *
 * Note: leftId and charValue are only meaningful for INSERT operations.
 * They are null for DELETE operations.
 */
public class OperationDTO {

    /**
     * The type of operation.
     * Must be either "INSERT" or "DELETE".
     */
    private String type;

    /**
     * The globally unique ID of the CRDT node this operation targets.
     * Format: "clientId:lamportClock"  e.g. "userA:42"
     *
     * For INSERT: the ID of the new node being created.
     * For DELETE: the ID of the existing node being tombstoned.
     */
    private String nodeId;

    /**
     * The ID of the left neighbour node at the time of insertion.
     * Null if the character is inserted at the start of the document.
     *
     * Only relevant for INSERT operations.
     */
    private String leftId;

    /**
     * The character being inserted.
     * Null for DELETE operations.
     */
    private Character charValue;

    /**
     * The ID of the client (user) who generated this operation.
     * Used together with lamportClock to form the unique nodeId.
     */
    private String clientId;

    /**
     * The Lamport logical clock value at the time this operation was generated.
     * Used for ordering and as part of the node ID.
     *
     * Lamport clock rules:
     *   - Increment on every local operation
     *   - On receiving a message: clock = max(local, received) + 1
     */
    private long lamportClock;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    public OperationDTO() {
        // Required by Jackson for JSON deserialization
    }

    public OperationDTO(String type, String nodeId, String leftId,
                        Character charValue, String clientId, long lamportClock) {
        this.type = type;
        this.nodeId = nodeId;
        this.leftId = leftId;
        this.charValue = charValue;
        this.clientId = clientId;
        this.lamportClock = lamportClock;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Factory helpers — cleaner than calling the constructor directly
    // ─────────────────────────────────────────────────────────────────────────

    public static OperationDTO insert(String nodeId, String leftId,
                                      char charValue, String clientId, long lamportClock) {
        return new OperationDTO("INSERT", nodeId, leftId, charValue, clientId, lamportClock);
    }

    public static OperationDTO delete(String nodeId, String clientId, long lamportClock) {
        return new OperationDTO("DELETE", nodeId, null, null, clientId, lamportClock);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters and Setters
    // ─────────────────────────────────────────────────────────────────────────

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getLeftId() { return leftId; }
    public void setLeftId(String leftId) { this.leftId = leftId; }

    public Character getCharValue() { return charValue; }
    public void setCharValue(Character charValue) { this.charValue = charValue; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public long getLamportClock() { return lamportClock; }
    public void setLamportClock(long lamportClock) { this.lamportClock = lamportClock; }

    @Override
    public String toString() {
        return "OperationDTO{" +
                "type='" + type + '\'' +
                ", nodeId='" + nodeId + '\'' +
                ", leftId='" + leftId + '\'' +
                ", charValue=" + charValue +
                ", clientId='" + clientId + '\'' +
                ", lamportClock=" + lamportClock +
                '}';
    }
}