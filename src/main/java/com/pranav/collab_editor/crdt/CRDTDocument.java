package com.pranav.collab_editor.crdt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

public class CRDTDocument {

    private final List<CRDTNode> nodes = new ArrayList<>();
    private final Map<String, CRDTNode> nodeIndex = new HashMap<>();

    // O(1) position lookup — updated by refreshPositionIndexFrom() after every insert
    private final Map<String, Integer> positionIndex = new HashMap<>();

    // Inserts waiting for their leftId to appear in nodeIndex
    private final Map<String, List<CRDTOperation>> waitingOn = new HashMap<>();

    // Deletes that arrived before their target node existed
    private final Set<String> pendingDeletes = new HashSet<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public synchronized void insert(CRDTOperation op) {
        if (!"INSERT".equals(op.getType())) {
            throw new IllegalArgumentException("Operation must be INSERT");
        }

        // Idempotent: ignore duplicates (can arrive from network replay)
        if (nodeIndex.containsKey(op.getNodeId())) {
            return;
        }

        // If the left-neighbour dependency is not yet present, buffer and wait
        if (op.getLeftId() != null && !nodeIndex.containsKey(op.getLeftId())) {
            waitingOn.computeIfAbsent(op.getLeftId(), k -> new ArrayList<>()).add(op);
            return;
        }

        applyInsert(op);

        // If a delete for this node arrived before the insert, apply it now
        if (pendingDeletes.remove(op.getNodeId())) {
            CRDTNode inserted = nodeIndex.get(op.getNodeId());
            if (inserted != null) {
                inserted.markDeleted();
            }
        }

        // This newly inserted node may unblock other buffered inserts
        replayWaitingOperations(op.getNodeId());
    }

    public synchronized void delete(CRDTOperation op) {
        if (!"DELETE".equals(op.getType())) {
            throw new IllegalArgumentException("Operation must be DELETE");
        }

        CRDTNode node = nodeIndex.get(op.getNodeId());
        if (node != null) {
            node.markDeleted();
        } else {
            // Insert not yet received — remember to tombstone it when it arrives
            pendingDeletes.add(op.getNodeId());
        }
    }

    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (CRDTNode node : nodes) {
            if (!node.isDeleted()) {
                sb.append(node.getValue());
            }
        }
        return sb.toString();
    }

    public List<CRDTNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public CRDTNode getNode(String nodeId) {
        return nodeIndex.get(nodeId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core insert algorithm
    // ─────────────────────────────────────────────────────────────────────────

    private void applyInsert(CRDTOperation op) {
        CRDTNode node = new CRDTNode(op.getNodeId(), op.getCharValue(), op.getLeftId());

        // Start just after the left neighbour (or at position 0 if no left neighbour)
        int insertIndex = 0;
        if (op.getLeftId() != null) {
            int leftIndex = findNodeIndex(op.getLeftId());
            insertIndex = leftIndex + 1;
        }

        // ── Sibling resolution ────────────────────────────────────────────────
        //
        // The scan considers two kinds of nodes:
        //
        // 1. DIRECT SIBLINGS — nodes whose leftId equals op.leftId.
        //    Concurrent inserts at the same position.
        //    Resolved by compareNodeIds(): whichever has the larger ID goes first.
        //    We stop when we find a sibling whose ID is larger than ours.
        //
        // 2. NON-SIBLINGS — nodes whose leftId differs from op.leftId.
        //    These are children (or deeper descendants) of some prior sibling.
        //    Example: B (leftId=A) when we insert X (leftId=null) and A < X.
        //    B belongs to A's subtree and must stay BEFORE X.
        //    We call belongsToSubtreeBefore() to detect this case.
        //    If yes  → skip past the node (it's part of an earlier subtree).
        //    If no   → stop here (we go before this node).
        //
        // Without this subtree check, concurrent sequences A→B and X→Y can
        // interleave into A,X,B,Y instead of the correct A,B,X,Y — a genuine
        // convergence bug where two clients end up with different documents.
        // ─────────────────────────────────────────────────────────────────────

        while (insertIndex < nodes.size()) {
            CRDTNode next = nodes.get(insertIndex);

            if (Objects.equals(next.getLeftId(), op.getLeftId())) {
                // Direct sibling — higher ID goes first
                if (compareNodeIds(next.getId(), op.getNodeId()) > 0) {
                    break; // next beats us — we go before it
                }
            } else {
                // Non-sibling — only skip if it belongs to an earlier subtree
                if (!belongsToSubtreeBefore(next, op.getLeftId(), op.getNodeId())) {
                    break;
                }
            }

            insertIndex++;
        }

        nodes.add(insertIndex, node);
        nodeIndex.put(node.getId(), node);

        // Update O(1) position cache for the new node and all nodes that shifted right
        refreshPositionIndexFrom(insertIndex);
    }

    /**
     * Returns true if {@code next} is a descendant of a prior sibling of the
     * node being inserted, meaning it belongs to a subtree that should appear
     * BEFORE the new node in the document.
     *
     * <p><b>Why this matters:</b><br>
     * Suppose we are inserting node O (leftId=L), and we encounter node N whose
     * leftId is not L.  N is a child of some ancestor P.  If P is a sibling of O
     * (P.leftId == L) and P sorts before O (compareNodeIds(P.id, O.id) &lt; 0),
     * then N — and the entire subtree rooted at P — must appear before O.
     * We skip past N.
     *
     * <p>If no such ancestor exists, O goes here (we stop scanning).
     *
     * @param next       the candidate node currently under inspection
     * @param opLeftId   the leftId of the node being inserted
     * @param opNodeId   the nodeId of the node being inserted (for tiebreaking)
     */
    private boolean belongsToSubtreeBefore(CRDTNode next, String opLeftId, String opNodeId) {
        String current = next.getLeftId();
        while (current != null) {
            CRDTNode ancestor = nodeIndex.get(current);
            if (ancestor == null) {
                return false;
            }
            if (Objects.equals(ancestor.getLeftId(), opLeftId)) {
                // `ancestor` is a direct sibling of op
                // It precedes op only if its own ID < op's ID
                return compareNodeIds(current, opNodeId) < 0;
            }
            current = ancestor.getLeftId();
        }
        // Reached document root without finding a common sibling parent
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Buffered-op replay
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * BFS through waitingOn using the newly-inserted nodeId as the seed.
     * Each op that becomes unblocked may cascade and unblock further ops.
     */
    private void replayWaitingOperations(String newlyAvailableNodeId) {
        Queue<String> ready = new LinkedList<>();
        ready.add(newlyAvailableNodeId);

        while (!ready.isEmpty()) {
            String availableId = ready.poll();

            List<CRDTOperation> waiting = waitingOn.remove(availableId);
            if (waiting == null || waiting.isEmpty()) {
                continue;
            }

            for (CRDTOperation pendingOp : waiting) {
                if (nodeIndex.containsKey(pendingOp.getNodeId())) {
                    continue; // already inserted (duplicate)
                }

                if (pendingOp.getLeftId() == null || nodeIndex.containsKey(pendingOp.getLeftId())) {
                    applyInsert(pendingOp);

                    if (pendingDeletes.remove(pendingOp.getNodeId())) {
                        CRDTNode inserted = nodeIndex.get(pendingOp.getNodeId());
                        if (inserted != null) {
                            inserted.markDeleted();
                        }
                    }

                    ready.add(pendingOp.getNodeId());
                } else {
                    // Still blocked — re-buffer under the correct dependency key
                    waitingOn.computeIfAbsent(pendingOp.getLeftId(), k -> new ArrayList<>())
                             .add(pendingOp);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private int findNodeIndex(String nodeId) {
        return positionIndex.getOrDefault(nodeId, -1);
    }

    /**
     * Refresh the O(1) position cache from startIndex to end-of-list.
     * Must be called after every insertion since everything at startIndex+
     * shifted right by one.
     *
     * For sequential appends (common when typing) startIndex is the last slot,
     * so this loop runs exactly once and is effectively O(1).
     */
    private void refreshPositionIndexFrom(int startIndex) {
        for (int i = startIndex; i < nodes.size(); i++) {
            positionIndex.put(nodes.get(i).getId(), i);
        }
    }

    /**
     * Deterministic, stable comparator for CRDT node IDs.
     *
     * <p>Node IDs have the format {@code "clientId:lamportClock"}, e.g. {@code "userA:42"}.
     *
     * <p><b>Why not {@code String.compareTo()}?</b><br>
     * Plain lexicographic comparison breaks for multi-digit clocks:<br>
     * {@code "A:10".compareTo("A:9")} is negative because {@code '1' < '9'},
     * so clock-10 would sort before clock-9.  All clients still converge (the
     * comparison is consistent), but the ordering is wrong and fragile across
     * clock-digit boundaries.
     *
     * <p>This method compares by (clientId ASC, lamportClock numerically ASC).
     */
    static int compareNodeIds(String idA, String idB) {
        int colonA = idA.lastIndexOf(':');
        int colonB = idB.lastIndexOf(':');

        String clientA = idA.substring(0, colonA);
        String clientB = idB.substring(0, colonB);

        int clientCmp = clientA.compareTo(clientB);
        if (clientCmp != 0) {
            return clientCmp;
        }

        long clockA = Long.parseLong(idA.substring(colonA + 1));
        long clockB = Long.parseLong(idB.substring(colonB + 1));
        return Long.compare(clockA, clockB);
    }
}