package com.pranav.collab_editor.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which user and document each active WebSocket session belongs to.
 *
 * The Problem:
 *   When a user closes their browser tab, Spring fires a SessionDisconnectEvent
 *   that contains only the WebSocket sessionId — a random string like "abc123".
 *   It does NOT tell us which user disconnected or which document they were editing.
 *   Without this information we cannot remove their cursor from Redis.
 *
 * The Solution:
 *   When a client sends their first cursor update, we record the mapping:
 *     sessionId → { userId, docId }
 *   When the disconnect event fires, we look up this mapping, find the user
 *   and document, and call PresenceService.removeCursor() to clean up Redis.
 *
 * Storage:
 *   ConcurrentHashMap — safe for concurrent WebSocket handler threads.
 *   Stored in-memory only (not Redis) because session mappings are
 *   inherently local to this server instance. If the server restarts,
 *   all sessions are gone anyway so there's nothing to clean up.
 *
 * Lifecycle:
 *   REGISTER → called from CollabController.handleCursor() on first cursor update
 *   LOOKUP   → called from CollabController.handleDisconnect() on tab close
 *   REMOVE   → called from CollabController.handleDisconnect() after cleanup
 */
@Component
public class SessionRegistry {

    /**
     * Holds the session information for a single WebSocket connection.
     * Immutable record — once registered a session's identity doesn't change.
     */
    public static class SessionInfo {
        private final String userId;
        private final String docId;

        public SessionInfo(String userId, String docId) {
            this.userId = userId;
            this.docId  = docId;
        }

        public String getUserId() { return userId; }
        public String getDocId()  { return docId; }

        @Override
        public String toString() {
            return "SessionInfo{userId='" + userId + "', docId='" + docId + "'}";
        }
    }

    // sessionId (Spring WebSocket session) → SessionInfo
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    /**
     * Registers a WebSocket session with a user and document.
     * Called when the client sends its first cursor update.
     * Safe to call multiple times for the same session — last write wins
     * (which is fine since userId and docId never change for a given session).
     *
     * @param sessionId the Spring WebSocket session ID
     * @param userId    the authenticated user's ID
     * @param docId     the document being edited in this session
     */
    public void register(String sessionId, String userId, String docId) {
        sessions.put(sessionId, new SessionInfo(userId, docId));
    }

    /**
     * Looks up the session info for a given WebSocket session ID.
     * Returns null if the session was never registered (e.g. the user
     * connected but never moved their cursor).
     *
     * @param sessionId the Spring WebSocket session ID
     * @return the SessionInfo or null if not found
     */
    public SessionInfo lookup(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Removes the session mapping after the user has disconnected.
     * Called after PresenceService.removeCursor() to free memory.
     *
     * @param sessionId the Spring WebSocket session ID to remove
     */
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * Returns the total number of currently tracked sessions.
     * Useful for debugging / monitoring.
     */
    public int size() {
        return sessions.size();
    }
}