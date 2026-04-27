package com.pranav.collab_editor.service;

import tools.jackson.databind.ObjectMapper;
import com.pranav.collab_editor.dto.CursorDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Manages real-time cursor presence using a ZSET + HASH Redis design.
 *
 * ── Why the old Hash+TTL design was broken ───────────────────────────────────
 *
 * The old design used a single Hash with a single TTL:
 *   HSET presence:{docId} userA {cursorJson}
 *   EXPIRE presence:{docId} 30
 *
 * Bug 1 — TTL Coupling (correctness bug):
 *   Every user's cursor is stored in ONE hash with ONE shared TTL.
 *   When userB moves their cursor, the TTL resets for the ENTIRE hash,
 *   keeping userA's stale cursor alive even after 30+ seconds of inactivity.
 *   Conversely, if userA is the only active user and the TTL fires, the whole
 *   hash is deleted — including userB's cursor which was just added.
 *   Each user needs to expire independently. A shared TTL cannot do that.
 *
 * Bug 2 — O(N) JSON parsing on every cursor move (performance bug):
 *   Every single cursor update triggered HGETALL → deserialize ALL N cursors
 *   → reserialize ALL N cursors for the broadcast. As users grow, every
 *   cursor move becomes more expensive. O(N) work for an O(1) operation.
 *
 * Bug 3 — Large broadcast payload (network bug):
 *   Broadcasting the full cursor list on every move is wasteful.
 *   10 users × 200 bytes/cursor × 10 moves/sec = 20KB/sec per document.
 *
 * ── The New ZSET + HASH Design ───────────────────────────────────────────────
 *
 * Two Redis structures per document:
 *
 *   1. ZSET  presence:{docId}
 *        member: userId
 *        score:  Unix timestamp of last cursor update (epoch seconds)
 *
 *      Example:
 *        ZADD presence:doc-123 1710000000 userA
 *        ZADD presence:doc-123 1710000020 userB
 *
 *      Purpose: activity tracking — who is active and WHEN they were last seen.
 *      ZRANGEBYSCORE lets us efficiently find stale users without scanning the hash.
 *
 *   2. HASH  presence:data:{docId}
 *        field: userId
 *        value: JSON-serialized CursorDTO
 *
 *      Example:
 *        HSET presence:data:doc-123 userA '{"line":3,"ch":7,...}'
 *        HSET presence:data:doc-123 userB '{"line":1,"ch":2,...}'
 *
 *      Purpose: cursor data storage — the actual position for each user.
 *
 * ── How each operation works ─────────────────────────────────────────────────
 *
 *   updateCursor(docId, userId, cursor):
 *     ZADD presence:{docId} {now} {userId}           ← update lastSeen timestamp
 *     HSET presence:data:{docId} {userId} {json}     ← update cursor data
 *     Each user's score is independent — no shared TTL.
 *
 *   getAllCursors(docId):
 *     cutoff = now - 30s
 *     staleUsers = ZRANGEBYSCORE presence:{docId} -inf cutoff   ← who hasn't moved in 30s?
 *     ZREMRANGEBYSCORE presence:{docId} -inf cutoff              ← evict from activity tracker
 *     HDEL presence:data:{docId} staleUser1 staleUser2 ...       ← evict cursor data
 *     activeUsers = ZRANGEBYSCORE presence:{docId} cutoff+1 +inf ← who is still active?
 *     HMGET presence:data:{docId} activeUser1 activeUser2 ...    ← fetch only their cursors
 *
 *   removeCursor(docId, userId):
 *     ZREM presence:{docId} {userId}                 ← remove from activity tracker
 *     HDEL presence:data:{docId} {userId}            ← remove cursor data
 *
 * ── Key improvements over Hash+TTL ──────────────────────────────────────────
 *
 *   ✅ Per-user expiry — each user's score is their own timestamp.
 *      userA going idle does NOT affect userB's presence.
 *
 *   ✅ Lazy cleanup — stale users are evicted during getAllCursors(),
 *      not by a background job or TTL daemon. No extra infrastructure needed.
 *
 *   ✅ O(1) cursor update — ZADD + HSET are both O(log N) and O(1) respectively.
 *      No full scan needed on write.
 *
 *   ✅ Selective fetch — HMGET fetches ONLY active users' cursors,
 *      not all users. If 2 of 10 users are stale, we parse 8 cursors not 10.
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    /**
     * How long (in seconds) a user's cursor is considered active
     * after their last update. If a user hasn't sent a cursor update
     * in this window, their cursor is treated as stale and removed
     * during the next getAllCursors() call.
     *
     * The client should send cursor pings at an interval shorter than this —
     * every 10 seconds is a good default (3× safety margin).
     */
    private static final int CURSOR_TTL_SECONDS = 30;

    @Autowired
    @Qualifier("redisTemplate")
    private RedisTemplate<String, String> redis;

    @Autowired
    private ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Write operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stores or updates a user's cursor position for a document.
     *
     * Redis operations (O(log N) + O(1)):
     *   ZADD presence:{docId} {nowEpochSec} {userId}
     *   HSET presence:data:{docId} {userId} {cursorJson}
     *
     * These two writes are independent — no shared TTL, no coupling.
     * UserA updating their cursor never touches userB's score or data.
     *
     * @param docId  the document being edited
     * @param userId the user whose cursor is being updated
     * @param cursor the cursor position data to store
     */
    public void updateCursor(String docId, String userId, CursorDTO cursor) {
        try {
            long nowEpochSec = Instant.now().getEpochSecond();
            String cursorJson = objectMapper.writeValueAsString(cursor);

            // Update the ZSET — set this user's lastSeen score to now
            redis.opsForZSet().add(zsetKey(docId), userId, nowEpochSec);

            // Update the HASH — store the actual cursor position data
            redis.opsForHash().put(hashKey(docId), userId, cursorJson);

            log.debug("Updated cursor: doc={} userId={} line={} ch={} lastSeen={}",
                    docId, userId, cursor.getLine(), cursor.getCh(), nowEpochSec);

        } catch (Exception e) {
            log.error("Failed to serialize cursor for userId={} doc={}", userId, docId, e);
        }
    }

    /**
     * Explicitly removes a user's cursor from a document.
     *
     * Called on clean WebSocket disconnects (tab closed, SessionDisconnectEvent).
     * For unclean disconnects (network loss, crash), the user will be evicted
     * lazily by getAllCursors() once their score becomes stale.
     *
     * Redis operations (O(log N) + O(1)):
     *   ZREM presence:{docId} {userId}
     *   HDEL presence:data:{docId} {userId}
     *
     * @param docId  the document to remove the cursor from
     * @param userId the user whose cursor to remove
     */
    public void removeCursor(String docId, String userId) {
        // Remove from activity tracker
        redis.opsForZSet().remove(zsetKey(docId), userId);

        // Remove cursor data
        redis.opsForHash().delete(hashKey(docId), userId);

        log.debug("Removed cursor: doc={} userId={}", docId, userId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all currently active cursors for a document, and evicts stale ones.
     *
     * "Active" means the user sent a cursor update within the last CURSOR_TTL_SECONDS.
     * This is determined by their score in the ZSET — scores older than the cutoff
     * are treated as stale.
     *
     * This method does lazy cleanup inline:
     *   1. Find stale users  (score < cutoff)
     *   2. Remove them from ZSET and HASH
     *   3. Find active users (score >= cutoff)
     *   4. Fetch their cursor data from the HASH
     *   5. Deserialize and return
     *
     * Redis operations:
     *   ZRANGEBYSCORE presence:{docId} -inf {cutoff-1}        → stale user IDs
     *   ZREMRANGEBYSCORE presence:{docId} -inf {cutoff-1}     → evict from ZSET
     *   HDEL presence:data:{docId} stale1 stale2 ...          → evict from HASH
     *   ZRANGEBYSCORE presence:{docId} {cutoff} +inf          → active user IDs
     *   HMGET presence:data:{docId} active1 active2 ...       → fetch cursor data
     *
     * @param docId the document to fetch active cursors for
     * @return list of CursorDTOs for all currently active users
     */
    public List<CursorDTO> getAllCursors(String docId) {
        long nowEpochSec = Instant.now().getEpochSecond();
        long cutoff      = nowEpochSec - CURSOR_TTL_SECONDS;

        // ── Step 1: Find and evict stale users ──────────────────────────────
        // ZRANGEBYSCORE with range (-inf, cutoff) → users not seen in 30s
        Set<String> staleUsers = redis.opsForZSet()
                .rangeByScore(zsetKey(docId), Double.NEGATIVE_INFINITY, cutoff);

        if (staleUsers != null && !staleUsers.isEmpty()) {
            log.debug("Evicting {} stale cursors from doc={}: {}", staleUsers.size(), docId, staleUsers);

            // Remove stale users from the ZSET in one call
            redis.opsForZSet()
                    .removeRangeByScore(zsetKey(docId), Double.NEGATIVE_INFINITY, cutoff);

            // Remove their cursor data from the HASH in one call
            // HDEL accepts varargs — one round-trip for all stale users
            redis.opsForHash()
                    .delete(hashKey(docId), staleUsers.toArray());
        }

        // ── Step 2: Find active users ────────────────────────────────────────
        // ZRANGEBYSCORE with range (cutoff, +inf) → users seen within 30s
        Set<String> activeUsers = redis.opsForZSet()
                .rangeByScore(zsetKey(docId), cutoff + 1, Double.POSITIVE_INFINITY);

        if (activeUsers == null || activeUsers.isEmpty()) {
            log.debug("No active cursors for doc={}", docId);
            return Collections.emptyList();
        }

        // ── Step 3: Fetch cursor data for active users only ──────────────────
        // HMGET fetches multiple hash fields in ONE round-trip
        // Only active users' data is fetched — stale data is already evicted above
        List<Object> rawValues = redis.opsForHash()
                .multiGet(hashKey(docId), new ArrayList<>(activeUsers));

        // ── Step 4: Deserialize ──────────────────────────────────────────────
        List<CursorDTO> cursors = new ArrayList<>();
        for (Object raw : rawValues) {
            if (raw == null) continue; // field was deleted between ZRANGEBYSCORE and HMGET

            try {
                CursorDTO cursor = objectMapper.readValue((String) raw, CursorDTO.class);
                cursors.add(cursor);
            } catch (Exception e) {
                log.warn("Failed to deserialize cursor JSON in doc={}: {}", docId, raw, e);
            }
        }

        log.debug("Returning {} active cursors for doc={}", cursors.size(), docId);
        return cursors;
    }

    /**
     * Returns the count of users currently active in a document.
     *
     * Uses ZCOUNT which is O(log N) — far cheaper than HGETALL + count.
     * "Active" means last seen within CURSOR_TTL_SECONDS.
     *
     * Note: this count may include users who have just gone stale but
     * haven't been evicted yet (lazy eviction happens in getAllCursors).
     * For an exact count, call getAllCursors().size() instead.
     *
     * @param docId the document to count active users for
     * @return approximate count of active users
     */
    public long getActiveUserCount(String docId) {
        long cutoff = Instant.now().getEpochSecond() - CURSOR_TTL_SECONDS;
        Long count = redis.opsForZSet()
                .count(zsetKey(docId), cutoff, Double.POSITIVE_INFINITY);
        return count == null ? 0 : count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Key helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Key for the ZSET that tracks per-user lastSeen timestamps.
     * Example: "presence:doc-abc-123"
     */
    private String zsetKey(String docId) {
        return "presence:" + docId;
    }

    /**
     * Key for the HASH that stores per-user cursor JSON data.
     * Example: "presence:data:doc-abc-123"
     *
     * Kept separate from the ZSET so the two structures can be inspected
     * independently in the Redis CLI during debugging:
     *   ZRANGE presence:doc-123 0 -1 WITHSCORES   → activity log
     *   HGETALL presence:data:doc-123              → cursor data
     */
    private String hashKey(String docId) {
        return "presence:data:" + docId;
    }
}