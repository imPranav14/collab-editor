package com.pranav.collab_editor.dto;

/**
 * Represents a single user's cursor position within a document.
 *
 * This DTO travels in two directions:
 *   Client → Server: sent to /app/document/{docId}/cursor when the cursor moves
 *   Server → Client: broadcast from /topic/document/{docId}/cursors as a list
 *
 * Stored in Redis as a JSON string inside a Hash:
 *   Key:   presence:{docId}
 *   Field: userId
 *   Value: JSON of this DTO
 *
 * Example JSON (wire format):
 * {
 *   "userId":    "user-uuid-abc",
 *   "username":  "pranav",
 *   "color":     "#3B82F6",
 *   "line":      5,
 *   "ch":        12,
 *   "documentId":"doc-uuid-xyz"
 * }
 *
 * line and ch follow the CodeMirror/ProseMirror convention:
 *   line → 0-based line number in the document
 *   ch   → 0-based character offset within that line
 */
public class CursorDTO {

    /**
     * The user's unique ID (from the JWT / User entity).
     * Used as the Redis hash field key so each user has exactly one
     * cursor entry per document.
     */
    private String userId;

    /**
     * Display name shown next to the cursor in the editor UI.
     */
    private String username;

    /**
     * Hex color for this user's cursor and selection highlight.
     * Comes from the User entity's color field (set on registration).
     * Example: "#3B82F6" (blue), "#EF4444" (red)
     */
    private String color;

    /**
     * 0-based line number of the cursor within the document.
     */
    private int line;

    /**
     * 0-based character offset within the line.
     */
    private int ch;

    /**
     * The document this cursor belongs to.
     * Redundant given it's stored under presence:{docId}, but
     * including it in the DTO makes the broadcast self-contained —
     * the client doesn't need to infer which document each cursor belongs to.
     */
    private String documentId;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    public CursorDTO() { /* Required by Jackson */ }

    public CursorDTO(String userId, String username, String color,
                     int line, int ch, String documentId) {
        this.userId     = userId;
        this.username   = username;
        this.color      = color;
        this.line       = line;
        this.ch         = ch;
        this.documentId = documentId;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters and Setters
    // ─────────────────────────────────────────────────────────────────────────

    public String getUserId()             { return userId; }
    public void setUserId(String u)       { this.userId = u; }

    public String getUsername()           { return username; }
    public void setUsername(String u)     { this.username = u; }

    public String getColor()              { return color; }
    public void setColor(String c)        { this.color = c; }

    public int getLine()                  { return line; }
    public void setLine(int l)            { this.line = l; }

    public int getCh()                    { return ch; }
    public void setCh(int c)              { this.ch = c; }

    public String getDocumentId()         { return documentId; }
    public void setDocumentId(String d)   { this.documentId = d; }

    @Override
    public String toString() {
        return "CursorDTO{userId='" + userId + "', username='" + username +
               "', line=" + line + ", ch=" + ch + '}';
    }
}