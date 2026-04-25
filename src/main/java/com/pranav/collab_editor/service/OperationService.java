package com.pranav.collab_editor.service;

import com.pranav.collab_editor.crdt.CRDTDocument;
import com.pranav.collab_editor.crdt.CRDTOperation;
import com.pranav.collab_editor.dto.OperationDTO;
import com.pranav.collab_editor.model.Operation;
import com.pranav.collab_editor.repository.OperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Handles persisting CRDT operations to PostgreSQL and replaying
 * the operation log to reconstruct document state.
 *
 * Two responsibilities:
 *
 *   1. PERSIST — every op that arrives via WebSocket gets saved here
 *      before being broadcast. This is the write path.
 *
 *   2. REPLAY — when a document is loaded (GET /api/documents/{id}),
 *      all persisted ops are fetched and re-applied to a fresh
 *      CRDTDocument to rebuild the current state. This is the read path.
 *
 * This separation of concerns keeps CollabController thin — it calls
 * persist() and lets this service handle all DB interaction.
 */
@Service
public class OperationService {

    private static final Logger log = LoggerFactory.getLogger(OperationService.class);

    @Autowired
    private OperationRepository operationRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // Write path — called from CollabController on every incoming op
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts an OperationDTO (wire format) to an Operation entity and
     * saves it to the operations table in PostgreSQL.
     *
     * @Transactional ensures the save either fully completes or rolls back.
     * If the DB is unavailable, the exception propagates and the op is
     * NOT broadcast — preventing clients from diverging from the persisted log.
     *
     * @param docId the document this operation belongs to
     * @param dto   the operation received from the WebSocket client
     * @return the saved Operation entity (with id and createdAt populated)
     */
    @Transactional
    public Operation persist(String docId, OperationDTO dto) {
        Operation operation = new Operation(
                docId,
                dto.getType(),
                dto.getNodeId(),
                dto.getLeftId(),
                dto.getCharValue(),
                dto.getClientId(),
                dto.getLamportClock()
        );

        Operation saved = operationRepository.save(operation);

        log.debug("Persisted op id={} type={} nodeId={} for doc={}",
                saved.getId(), saved.getOpType(), saved.getNodeId(), docId);

        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read path — called from DocumentController on document load
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches all operations for a document from PostgreSQL in Lamport clock
     * order and replays them against a fresh CRDTDocument.
     *
     * This reconstructs the full document state from scratch — exactly what
     * happens after a server restart when the in-memory CRDT is gone.
     *
     * The resulting CRDTDocument is also loaded into CRDTService's in-memory
     * cache so subsequent WebSocket ops for this document apply immediately
     * without another DB round-trip.
     *
     * Performance note:
     *   For large documents (10,000+ ops) this can be slow. Phase 9 (Snapshots)
     *   will address this by storing periodic full-state checkpoints so only
     *   ops AFTER the latest snapshot need to be replayed.
     *
     * @param docId       the document to load and replay
     * @param crdtService the service that holds the in-memory CRDT cache
     * @return the fully replayed CRDTDocument (also cached in crdtService)
     */
    @Transactional(readOnly = true)
    public CRDTDocument replayAll(String docId, CRDTService crdtService) {
        List<Operation> ops = operationRepository
                .findByDocumentIdOrderByLamportClockAsc(docId);

        log.info("Replaying {} operations for doc={}", ops.size(), docId);

        CRDTDocument doc = new CRDTDocument();

        for (Operation op : ops) {
            CRDTOperation crdtOp = toCRDTOperation(op);

            if ("INSERT".equals(op.getOpType())) {
                doc.insert(crdtOp);
            } else if ("DELETE".equals(op.getOpType())) {
                doc.delete(crdtOp);
            }
        }

        log.info("Replay complete for doc={}. Text length={}", docId, doc.getText().length());

        // Cache the replayed document in-memory so subsequent WebSocket ops
        // apply to this already-built state rather than starting from scratch
        crdtService.loadDocument(docId, doc);

        return doc;
    }

    /**
     * Fetches all raw Operation entities for a document in order.
     * Used by DocumentController to include them in the DocumentLoadResponse
     * so the CLIENT can also replay them on their local CRDT.
     *
     * @param docId the document to fetch ops for
     * @return ordered list of all persisted operations
     */
    @Transactional(readOnly = true)
    public List<Operation> findAllForDocument(String docId) {
        return operationRepository.findByDocumentIdOrderByLamportClockAsc(docId);
    }

    /**
     * Returns the total count of persisted operations for a document.
     * Used in Phase 9 to decide when to trigger a snapshot.
     *
     * @param docId the document to count ops for
     * @return total operation count
     */
    public long countForDocument(String docId) {
        return operationRepository.countByDocumentId(docId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts a persisted Operation entity back into a CRDTOperation
     * that can be passed to CRDTDocument.insert() or CRDTDocument.delete().
     */
    private CRDTOperation toCRDTOperation(Operation op) {
        return new CRDTOperation(
                op.getOpType(),
                op.getNodeId(),
                op.getLeftId(),
                op.getCharValue(),
                op.getClientId(),
                op.getLamportClock()
        );
    }
}