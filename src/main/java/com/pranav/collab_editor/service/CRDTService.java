package com.pranav.collab_editor.service;

import com.pranav.collab_editor.crdt.CRDTDocument;
import com.pranav.collab_editor.crdt.CRDTOperation;
import com.pranav.collab_editor.dto.OperationDTO;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Server-side CRDT state manager.
 *
 * The server maintains one CRDTDocument per active document in memory.
 * Every operation received from any client is applied here so the server
 * always holds the current authoritative document state.
 *
 * This serves two purposes:
 *   1. The server can reconstruct the full document text at any time
 *      (needed for snapshots in Phase 9 and initial load in Phase 5).
 *   2. The server validates that operations are structurally well-formed
 *      before broadcasting them to other clients.
 *
 * Thread safety:
 *   ConcurrentHashMap ensures safe concurrent access to the activeDocuments map
 *   when multiple WebSocket handler threads process ops for different documents.
 *   Each CRDTDocument's insert() and delete() methods are synchronized internally,
 *   so concurrent ops for the SAME document are also safe.
 */
@Service
public class CRDTService {

    /**
     * In-memory store of active documents.
     * Key:   documentId (UUID string)
     * Value: the live CRDTDocument for that document
     *
     * Documents are lazily initialized on first access.
     * In Phase 5, documents will be loaded from the PostgreSQL operation log
     * instead of starting empty.
     */
    private final Map<String, CRDTDocument> activeDocuments = new ConcurrentHashMap<>();

    /**
     * Applies an operation to the server-side CRDT for the given document.
     *
     * If no CRDTDocument exists for this docId yet, one is created (lazy init).
     * The OperationDTO is converted to a CRDTOperation and applied to the document.
     *
     * @param docId the document this operation belongs to
     * @param op    the operation to apply (INSERT or DELETE)
     * @throws IllegalArgumentException if op.type is not INSERT or DELETE
     */
    public void apply(String docId, OperationDTO op) {
        // Get existing document or create a fresh one for this docId
        CRDTDocument doc = activeDocuments.computeIfAbsent(docId, id -> new CRDTDocument());

        // Convert the DTO (wire format) to the internal CRDTOperation
        CRDTOperation crdtOp = new CRDTOperation(
                op.getType(),
                op.getNodeId(),
                op.getLeftId(),
                op.getCharValue(),
                op.getClientId(),
                op.getLamportClock()
        );

        if ("INSERT".equals(op.getType())) {
            doc.insert(crdtOp);
        } else if ("DELETE".equals(op.getType())) {
            doc.delete(crdtOp);
        } else {
            throw new IllegalArgumentException("Unknown operation type: " + op.getType());
        }
    }

    /**
     * Returns the current visible text of the document.
     * Returns an empty string if no document exists for this docId yet.
     *
     * Useful for debugging and will be used in Phase 9 for snapshots.
     *
     * @param docId the document to get text for
     * @return the current text content, or "" if not loaded
     */
    public String getText(String docId) {
        CRDTDocument doc = activeDocuments.get(docId);
        return doc == null ? "" : doc.getText();
    }

    /**
     * Returns the CRDTDocument for a given docId, or null if not loaded.
     * Will be used in Phase 5 when loading documents from the operation log.
     *
     * @param docId the document ID
     * @return the live CRDTDocument, or null
     */
    public CRDTDocument getDocument(String docId) {
        return activeDocuments.get(docId);
    }

    /**
     * Loads a document into memory from an already-constructed CRDTDocument.
     * Will be called from Phase 5 when replaying the operation log on startup.
     *
     * @param docId    the document ID
     * @param document the pre-built CRDTDocument
     */
    public void loadDocument(String docId, CRDTDocument document) {
        activeDocuments.put(docId, document);
    }

    /**
     * Removes a document from memory (e.g. when all users disconnect).
     * The operation log in PostgreSQL (Phase 5) is the permanent store;
     * this just frees up memory for inactive documents.
     *
     * @param docId the document to evict
     */
    public void evictDocument(String docId) {
        activeDocuments.remove(docId);
    }
}