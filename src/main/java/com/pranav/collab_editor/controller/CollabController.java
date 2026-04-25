package com.pranav.collab_editor.controller;

import com.pranav.collab_editor.dto.OperationDTO;
import com.pranav.collab_editor.service.CRDTService;
import com.pranav.collab_editor.service.OperationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * WebSocket message handler for real-time collaborative editing.
 *
 * Phase 4: received op → apply to in-memory CRDT → broadcast.
 * Phase 5: received op → apply to in-memory CRDT → PERSIST TO DB → broadcast.
 *
 * The persistence step (step 2) is intentionally placed BEFORE the broadcast
 * (step 3). If the DB write fails, the exception propagates and the broadcast
 * never happens. This prevents clients from applying an op that the server
 * failed to record — keeping in-memory state and persisted state in sync.
 *
 * If we broadcast first and then the DB write fails, clients would have an
 * op in their local CRDT that doesn't exist in the operation log. On the next
 * document load, those clients would see a different document than others.
 */
@Controller
public class CollabController {

    private static final Logger log = LoggerFactory.getLogger(CollabController.class);

    @Autowired
    private CRDTService crdtService;

    @Autowired
    private OperationService operationService;   // ← added in Phase 5

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Handles an incoming CRDT operation from a WebSocket client.
     *
     * Flow:
     *   1. Client sends OperationDTO to /app/document/{docId}/op
     *   2. Apply to server-side in-memory CRDT
     *   3. Persist to PostgreSQL operations table   ← Phase 5 addition
     *   4. Broadcast to /topic/document/{docId}
     *
     * @param docId the document ID from the destination URL
     * @param op    the CRDT operation (auto-deserialized from JSON by Spring)
     */
    @MessageMapping("/document/{docId}/op")
    public void handleOperation(
            @DestinationVariable String docId,
            @Payload OperationDTO op) {

        log.debug("Received {} op — doc={} nodeId={} client={}",
                op.getType(), docId, op.getNodeId(), op.getClientId());

        // Step 1 — Apply to server-side in-memory CRDT
        // Keeps the server's document state current so getText() is always accurate.
        crdtService.apply(docId, op);

        // Step 2 — Persist to PostgreSQL
        // Saves the op to the operations table BEFORE broadcasting.
        // If this throws (DB down, constraint violation, etc.) the broadcast
        // is skipped — clients stay in sync with what was actually persisted.
        operationService.persist(docId, op);

        log.debug("Persisted op. Doc text now: '{}'", crdtService.getText(docId));

        // Step 3 — Broadcast to all subscribers of this document
        // All connected clients apply this op to their local CRDTs.
        messagingTemplate.convertAndSend("/topic/document/" + docId, op);

        log.debug("Broadcasted op to /topic/document/{}", docId);
    }
}