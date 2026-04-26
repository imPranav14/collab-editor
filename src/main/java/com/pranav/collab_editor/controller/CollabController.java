package com.pranav.collab_editor.controller;

import com.pranav.collab_editor.dto.CursorDTO;
import com.pranav.collab_editor.dto.OperationDTO;
import com.pranav.collab_editor.service.CRDTService;
import com.pranav.collab_editor.service.OperationService;
import com.pranav.collab_editor.service.PresenceService;
import com.pranav.collab_editor.service.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;

/**
 * WebSocket message handler for real-time collaborative editing.
 *
 * This file is unchanged from Phase 6 (old design) in terms of structure.
 * The only difference is that PresenceService now uses ZSET+HASH internally —
 * this controller calls the same three methods: updateCursor, getAllCursors,
 * removeCursor. The improvement is entirely inside PresenceService.
 *
 * Destinations handled:
 *   /app/document/{docId}/op      → handleOperation()
 *   /app/document/{docId}/cursor  → handleCursor()
 *
 * Broadcasts emitted:
 *   /topic/document/{docId}          → OperationDTO (single op)
 *   /topic/document/{docId}/cursors  → List<CursorDTO> (all active cursors)
 */
@Controller
public class CollabController {

    private static final Logger log = LoggerFactory.getLogger(CollabController.class);

    @Autowired private CRDTService          crdtService;
    @Autowired private OperationService     operationService;
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private PresenceService      presenceService;
    @Autowired private SessionRegistry      sessionRegistry;

    // =========================================================================
    // Phase 4 + 5 — CRDT operation handling (unchanged)
    // =========================================================================

    /**
     * Handles an incoming CRDT operation from a client.
     *
     * Flow:
     *   1. Apply to server-side in-memory CRDT
     *   2. Persist to PostgreSQL
     *   3. Broadcast to all document subscribers
     */
    @MessageMapping("/document/{docId}/op")
    public void handleOperation(
            @DestinationVariable String docId,
            @Payload OperationDTO op) {

        log.debug("Op received — doc={} type={} nodeId={}", docId, op.getType(), op.getNodeId());

        crdtService.apply(docId, op);
        operationService.persist(docId, op);
        messagingTemplate.convertAndSend("/topic/document/" + docId, op);
    }

    // =========================================================================
    // Phase 6 — Cursor presence (ZSET+HASH design)
    // =========================================================================

    /**
     * Handles a cursor position update from a client.
     *
     * The ZSET+HASH design changes what happens inside PresenceService,
     * but this handler's flow is the same:
     *
     *   1. Register the session → userId/docId mapping (for disconnect cleanup)
     *   2. updateCursor()   → ZADD (score=now) + HSET (cursor JSON)
     *   3. getAllCursors()  → evict stale users, HMGET active users only
     *   4. Broadcast the active cursor list to all document subscribers
     *
     * Performance difference from old design:
     *   OLD: HGETALL (all N cursors) + deserialize N + reserialize N
     *   NEW: evict stale O(stale), HMGET active only O(active) + deserialize active
     *        If 10 users, 2 stale → only 8 cursors deserialized instead of 10
     *
     * @param docId    document being edited
     * @param cursor   cursor position sent by the client
     * @param accessor provides the WebSocket session ID for disconnect tracking
     */
    @MessageMapping("/document/{docId}/cursor")
    public void handleCursor(
            @DestinationVariable String docId,
            @Payload CursorDTO cursor,
            SimpMessageHeaderAccessor accessor) {

        String sessionId = accessor.getSessionId();

        // Step 1 — Register session so disconnect handler knows who this is
        sessionRegistry.register(sessionId, cursor.getUserId(), docId);

        // Step 2 — Write to Redis: ZADD (lastSeen=now) + HSET (cursor JSON)
        presenceService.updateCursor(docId, cursor.getUserId(), cursor);

        // Step 3 — Read active cursors: evict stale, HMGET remaining
        List<CursorDTO> allCursors = presenceService.getAllCursors(docId);

        // Step 4 — Broadcast updated cursor list to all clients on this document
        messagingTemplate.convertAndSend(
                "/topic/document/" + docId + "/cursors", allCursors);

        log.debug("Cursor update broadcast: doc={} activeUsers={}", docId, allCursors.size());
    }

    /**
     * Handles a WebSocket session disconnect (tab closed or connection lost).
     *
     * Flow:
     *   1. Look up (userId, docId) from the sessionId in SessionRegistry
     *   2. ZREM + HDEL → remove user from both Redis structures immediately
     *   3. Broadcast updated cursor list (without the disconnected user)
     *   4. Clean up session mapping from SessionRegistry
     *
     * For unclean disconnects where this event never fires (network drop,
     * browser crash), the user's cursor is evicted lazily by getAllCursors()
     * once their ZSET score becomes older than CURSOR_TTL_SECONDS.
     *
     * @param event Spring's disconnect event containing the sessionId
     */
    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        SessionRegistry.SessionInfo info = sessionRegistry.lookup(sessionId);

        if (info == null) {
            // User never sent a cursor — nothing to clean up
            return;
        }

        String userId = info.getUserId();
        String docId  = info.getDocId();

        log.info("Disconnect: userId={} doc={}", userId, docId);

        // ZREM + HDEL — remove from both Redis structures
        presenceService.removeCursor(docId, userId);

        // Broadcast updated cursor list — this one will not include the disconnected user
        List<CursorDTO> remaining = presenceService.getAllCursors(docId);
        messagingTemplate.convertAndSend(
                "/topic/document/" + docId + "/cursors", remaining);

        sessionRegistry.remove(sessionId);
    }
}