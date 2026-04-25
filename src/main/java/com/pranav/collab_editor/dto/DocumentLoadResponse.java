package com.pranav.collab_editor.dto;

import com.pranav.collab_editor.model.Operation;

import java.util.List;

/**
 * Response body returned by GET /api/documents/{id}.
 *
 * This is the payload a client receives when it first opens a document.
 * It contains everything the client needs to reconstruct the current
 * document state from scratch in the browser:
 *
 *   1. documentId    — so the client knows which document it loaded
 *   2. title         — displayed in the browser tab / header
 *   3. operations    — the full ordered list of every INSERT and DELETE
 *                      ever applied to this document
 *
 * The client iterates over operations in order and replays each one
 * against its local (JavaScript) CRDTDocument. After replaying all ops,
 * the client's CRDT state exactly matches the server's.
 *
 * Phase 9 (Snapshots) will extend this with:
 *   - snapshot.content  → serialized CRDT state at a checkpoint (fast to load)
 *   - snapshot.lastOpId → only ops with id > lastOpId need to be replayed
 * This avoids replaying 10,000 ops every time on large documents.
 *
 * Example JSON response:
 * {
 *   "documentId": "abc-123",
 *   "title": "My Document",
 *   "operations": [
 *     { "opType":"INSERT", "nodeId":"userA:1", "leftId":null, "charValue":"H", ... },
 *     { "opType":"INSERT", "nodeId":"userA:2", "leftId":"userA:1", "charValue":"i", ... },
 *     { "opType":"DELETE", "nodeId":"userA:1", ... }
 *   ]
 * }
 */
public class DocumentLoadResponse {

    /** The document's unique ID. */
    private String documentId;

    /** Human-readable document title. */
    private String title;

    /**
     * Ordered list of all operations for this document (Lamport clock ASC).
     * The client must replay these in the order they appear in this list.
     */
    private List<Operation> operations;

    /**
     * The current text of the document as computed by the server.
     * Included as a convenience/sanity-check field — the client should
     * derive its own text from replaying operations, but this lets the
     * client quickly verify its replay produced the correct result.
     */
    private String currentText;

    /**
     * Total number of operations in the log.
     * Useful for the client to show a progress indicator while replaying.
     */
    private long operationCount;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    public DocumentLoadResponse() { /* Required by Jackson */ }

    public DocumentLoadResponse(String documentId, String title,
                                List<Operation> operations, String currentText) {
        this.documentId     = documentId;
        this.title          = title;
        this.operations     = operations;
        this.currentText    = currentText;
        this.operationCount = operations == null ? 0 : operations.size();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters and Setters
    // ─────────────────────────────────────────────────────────────────────────

    public String getDocumentId()              { return documentId; }
    public void setDocumentId(String d)        { this.documentId = d; }

    public String getTitle()                   { return title; }
    public void setTitle(String t)             { this.title = t; }

    public List<Operation> getOperations()     { return operations; }
    public void setOperations(List<Operation> o) {
        this.operations     = o;
        this.operationCount = o == null ? 0 : o.size();
    }

    public String getCurrentText()             { return currentText; }
    public void setCurrentText(String t)       { this.currentText = t; }

    public long getOperationCount()            { return operationCount; }
    public void setOperationCount(long c)      { this.operationCount = c; }
}