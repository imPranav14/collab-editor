package com.pranav.collab_editor.repository;

import com.pranav.collab_editor.model.Operation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The primary query pattern for this project is:
 *   "Fetch all operations for document X in Lamport clock order"
 * This is what gets called on every document load to replay the op log.
 */
@Repository
public interface OperationRepository extends JpaRepository<Operation, Long> {

    /**
     * Fetches the full operation log for a document in causal order.
     *
     * Ordering by lamportClock ASC is critical — replaying ops in
     * logical time order guarantees the CRDT converges to the correct
     * state. Replaying in wall-clock (createdAt) order could produce
     * wrong results if clocks are skewed across clients.
     *
     * This query is covered by the composite index:
     *   idx_ops_document_clock (document_id, lamport_clock)
     * so it stays fast even on documents with tens of thousands of ops.
     *
     * Used in:
     *   - OperationService.replayAll()    → full replay on document load
     *   - DocumentController.loadDocument() → building DocumentLoadResponse
     *
     * @param documentId the document whose ops to fetch
     * @return ordered list of all operations for the document
     */
    List<Operation> findByDocumentIdOrderByLamportClockAsc(String documentId);

    /**
     * Fetches only the ops that came AFTER a given operation ID.
     *
     * Used in Phase 9 (Snapshots):
     *   When a snapshot exists for op ID N, the client loads the snapshot
     *   (fast) and then only needs to replay ops with id > N (cheap).
     *   Without this, every document load replays from op #1.
     *
     * @param documentId the document whose ops to fetch
     * @param lastOpId   replay ops with id strictly greater than this value
     * @return ordered list of ops after the snapshot point
     */
    List<Operation> findByDocumentIdAndIdGreaterThanOrderByLamportClockAsc(
            String documentId, Long lastOpId);

    /**
     * Counts the total number of operations for a document.
     *
     * Used in Phase 9 to decide when to take a new snapshot:
     * if (count % 500 == 0) → take snapshot
     *
     * @param documentId the document to count ops for
     * @return total number of operations persisted for this document
     */
    long countByDocumentId(String documentId);

    /**
     * Deletes all operations for a document.
     *
     * Used when a document is deleted (cleanup).
     * Not called during normal editing flow.
     *
     * @param documentId the document whose ops to delete
     */
    void deleteByDocumentId(String documentId);
}