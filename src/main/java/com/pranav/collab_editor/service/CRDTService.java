package com.pranav.collab_editor.service;

import com.pranav.collab_editor.crdt.CRDTDocument;
import com.pranav.collab_editor.crdt.CRDTOperation;
import com.pranav.collab_editor.dto.OperationDTO;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side CRDT state manager.
 *
 * Maintains one CRDTDocument per active document in memory.
 * Every operation received from any client is applied here so the server
 * always holds the current authoritative in-memory document state.
 *
 * Phase 4: documents started empty and were never reloaded after restart.
 * Phase 5: documents are loaded from the PostgreSQL operation log on first
 *           access via replayAll() in OperationService, then cached here.
 *
 * Thread safety:
 *   ConcurrentHashMap makes activeDocuments map safe for concurrent threads.
 *   Each CRDTDocument's insert() and delete() are synchronized internally.
 */
@Service
public class CRDTService {

    /**
     * In-memory store of active documents.
     * Key:   documentId (String)
     * Value: the live CRDTDocument
     *
     * Phase 4: populated lazily on first WebSocket op (always starts empty).
     * Phase 5: populated by loadDocument() after replaying the op log,
     *           OR lazily if a WebSocket op arrives before the document is loaded.
     */
    private final Map<String, CRDTDocument> activeDocuments = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Core apply — called from CollabController on every incoming WebSocket op
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies an operation to the server-side in-memory CRDT.
     *
     * If the document is not yet in memory (e.g. the server just restarted
     * and a WebSocket op arrived before the REST load endpoint was called),
     * a new empty CRDTDocument is created. The persisted ops will be replayed
     * when the client calls GET /api/documents/{id}.
     *
     * @param docId the document to apply the op to
     * @param dto   the operation received from the WebSocket client
     */
    public void apply(String docId, OperationDTO dto) {
        CRDTDocument doc = activeDocuments.computeIfAbsent(docId, id -> new CRDTDocument());

        CRDTOperation crdtOp = new CRDTOperation(
                dto.getType(),
                dto.getNodeId(),
                dto.getLeftId(),
                dto.getCharValue(),
                dto.getClientId(),
                dto.getLamportClock()
        );

        if ("INSERT".equals(dto.getType())) {
            doc.insert(crdtOp);
        } else if ("DELETE".equals(dto.getType())) {
            doc.delete(crdtOp);
        } else {
            throw new IllegalArgumentException("Unknown operation type: " + dto.getType());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache management — called from OperationService after replaying op log
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stores a pre-built CRDTDocument in the in-memory cache.
     *
     * Called by OperationService.replayAll() after it has replayed the full
     * PostgreSQL operation log into a fresh CRDTDocument. This makes the
     * document immediately available for subsequent WebSocket ops without
     * another DB round-trip.
     *
     * @param docId    the document ID
     * @param document the fully replayed CRDTDocument
     */
    public void loadDocument(String docId, CRDTDocument document) {
        activeDocuments.put(docId, document);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the current visible text of the document.
     * Returns an empty string if the document is not loaded in memory.
     *
     * @param docId the document to get text for
     * @return current text content or "" if not loaded
     */
    public String getText(String docId) {
        CRDTDocument doc = activeDocuments.get(docId);
        return doc == null ? "" : doc.getText();
    }

    /**
     * Returns the CRDTDocument for the given docId, or null if not loaded.
     *
     * @param docId the document ID
     * @return the live CRDTDocument or null
     */
    public CRDTDocument getDocument(String docId) {
        return activeDocuments.get(docId);
    }

    /**
     * Returns true if the document is currently held in memory.
     * Useful for deciding whether to replay the op log before applying a new op.
     *
     * @param docId the document ID
     * @return true if loaded in memory
     */
    public boolean isLoaded(String docId) {
        return activeDocuments.containsKey(docId);
    }

    /**
     * Removes a document from memory.
     * The operation log in PostgreSQL is the permanent record.
     * This just frees RAM for inactive documents.
     *
     * @param docId the document to evict
     */
    public void evictDocument(String docId) {
        activeDocuments.remove(docId);
    }
}