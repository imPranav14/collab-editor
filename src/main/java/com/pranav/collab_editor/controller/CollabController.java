package com.pranav.collab_editor.controller;

import com.pranav.collab_editor.dto.OperationDTO;
import com.pranav.collab_editor.service.CRDTService;
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
 * This controller handles the core real-time sync loop:
 *   1. A client sends a CRDT operation (INSERT or DELETE)
 *   2. The server applies it to the server-side CRDT (for consistency)
 *   3. The server broadcasts it to ALL clients on the same document
 *      (including the sender — the client handles deduplication)
 *
 * Why broadcast back to the sender too?
 *   The sender applied the op optimistically already. Receiving it back
 *   acts as a server acknowledgement. The client's CRDT is idempotent
 *   (duplicate ops are safely ignored), so this causes no issues.
 *
 * Destination conventions:
 *   Client sends to:      /app/document/{docId}/op
 *   Server broadcasts to: /topic/document/{docId}
 *
 *   The /app prefix is stripped by Spring before matching @MessageMapping.
 *   So @MessageMapping("/document/{docId}/op") matches /app/document/{docId}/op.
 */
@Controller
public class CollabController {

    private static final Logger log = LoggerFactory.getLogger(CollabController.class);

    /**
     * Applies ops to the server-side in-memory CRDT.
     * Maintains one CRDTDocument per active document.
     */
    @Autowired
    private CRDTService crdtService;

    /**
     * Sends messages to specific STOMP destinations.
     * Used here to broadcast ops to all subscribers of a document topic.
     */
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Handles an incoming CRDT operation from a client.
     *
     * Flow:
     *   1. Receive OperationDTO from /app/document/{docId}/op
     *   2. Apply it to the server-side CRDT for document docId
     *   3. Broadcast the same op to /topic/document/{docId}
     *      (all subscribers, including the sender, receive it)
     *
     * In Phase 5 (Persistence), step 2.5 will be added:
     *   2.5 Persist the op to the PostgreSQL operation log
     *
     * @param docId  the document ID extracted from the destination URL
     * @param op     the CRDT operation sent by the client (auto-deserialized from JSON)
     */
    @MessageMapping("/document/{docId}/op")
    public void handleOperation(
            @DestinationVariable String docId,
            @Payload OperationDTO op) {

        log.debug("Received {} op for doc={} nodeId={} from client={}",
                op.getType(), docId, op.getNodeId(), op.getClientId());

        // Step 1 — Apply the operation to the server-side CRDT.
        // This keeps the server in sync with all clients and lets it
        // reconstruct the full document text at any time.
        crdtService.apply(docId, op);

        log.debug("Applied op to server CRDT. Document text for doc={}: '{}'",
                docId, crdtService.getText(docId));

        // Step 2 — Broadcast the op to every client subscribed to this document.
        // All clients apply this op to their own local CRDT and re-render.
        messagingTemplate.convertAndSend("/topic/document/" + docId, op);

        log.debug("Broadcasted op to /topic/document/{}", docId);
    }
}