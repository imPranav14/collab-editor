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

    // O(1) position lookup — maintained by refreshPositionIndexFrom() after every insert
    private final Map<String, Integer> positionIndex = new HashMap<>();

    // Inserts waiting for their leftId to exist in nodeIndex
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

        // Idempotent: ignore duplicate ops (can arrive from network replay)
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

        // Start scanning from just after the left neighbour (or from 0 if no left neighbour)
        int insertIndex = 0;
        if (op.getLeftId() != null) {
            int leftIndex = findNodeIndex(op.getLeftId());
            insertIndex = leftIndex + 1;
        }

        // Resolve concurrent siblings: multiple nodes with the same leftId
        // were inserted at the same position simultaneously.
        // We use compareNodeIds() as a deterministic, stable tiebreaker so that
        // ALL clients resolve ties to the same ordering regardless of arrival order.
        //
        // Rule: scan right while the next node is a sibling AND sorts before us.
        // Stop as soon as we find a node that should come AFTER us.
        while (insertIndex < nodes.size()) {
            CRDTNode next = nodes.get(insertIndex);

            // Not a sibling — different left neighbour, so we've passed the sibling group
            if (!Objects.equals(next.getLeftId(), op.getLeftId())) {
                break;
            }

            // Same sibling group: the node with the "larger" ID wins and goes first.
            // If the next node's ID is greater than ours we stop — we go before it.
            if (compareNodeIds(next.getId(), op.getNodeId()) > 0) {
                break;
            }

            insertIndex++;
        }

        nodes.add(insertIndex, node);
        nodeIndex.put(node.getId(), node);

        // Update the O(1) position cache for the new node and every node after it
        // (they all shifted right by one due to the insertion)
        refreshPositionIndexFrom(insertIndex);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Buffered-op replay
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * BFS through the waitingOn map, using the newly-available nodeId as the
     * starting seed. Each op that becomes unblocked may in turn unblock more ops.
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
                // Guard against the (rare) case of a duplicate arriving via replay
                if (nodeIndex.containsKey(pendingOp.getNodeId())) {
                    continue;
                }

                // leftId is available — go ahead and insert
                if (pendingOp.getLeftId() == null || nodeIndex.containsKey(pendingOp.getLeftId())) {
                    applyInsert(pendingOp);

                    // Apply any pending delete for this newly inserted node
                    if (pendingDeletes.remove(pendingOp.getNodeId())) {
                        CRDTNode inserted = nodeIndex.get(pendingOp.getNodeId());
                        if (inserted != null) {
                            inserted.markDeleted();
                        }
                    }

                    // This node may unblock yet more buffered ops
                    ready.add(pendingOp.getNodeId());
                } else {
                    // Still waiting on a different dependency — re-buffer under the real key
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
     * Must be called after every insertion — everything at startIndex and beyond
     * has shifted right by one.
     *
     * For sequential appends (the common case when typing) startIndex == nodes.size()-1,
     * so this loop runs exactly once and the whole operation is O(1).
     */
    private void refreshPositionIndexFrom(int startIndex) {
        for (int i = startIndex; i < nodes.size(); i++) {
            positionIndex.put(nodes.get(i).getId(), i);
        }
    }

    /**
     * Deterministic, stable comparator for CRDT node IDs.
     *
     * Node IDs have the format  "clientId:lamportClock"  e.g. "userA:42".
     *
     * WHY NOT String.compareTo()?
     * Plain lexicographic comparison breaks for multi-digit clocks:
     *   "A:10".compareTo("A:9")  →  negative  (because '1' < '9')
     * This means clock 10 sorts BEFORE clock 9, which is wrong.
     * All clients still converge (the comparison is at least consistent), but
     * the sibling ordering would be counter-intuitive and fragile.
     *
     * This method compares by (clientId ASC, lamportClock ASC) using the
     * numeric value of the clock, which is both correct and intuitive.
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