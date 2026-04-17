package com.pranav.collab_editor.crdt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.HashSet;

public class CRDTDocument {

    private final List<CRDTNode> nodes = new ArrayList<>();
    private final Map<String, CRDTNode> nodeIndex = new HashMap<>();

    // Waiting inserts grouped by the leftId they depend on
    private final Map<String, List<CRDTOperation>> waitingOn = new HashMap<>();

    // Deletes that arrived before the target node
    private final Set<String> pendingDeletes = new HashSet<>();

    public synchronized void insert(CRDTOperation op) {
        if (!"INSERT".equals(op.getType())) {
            throw new IllegalArgumentException("Operation must be INSERT");
        }

        if (nodeIndex.containsKey(op.getNodeId())) {
            return; // idempotent: already inserted
        }

        // If dependency is missing, buffer this op
        if (op.getLeftId() != null && !nodeIndex.containsKey(op.getLeftId())) {
            waitingOn.computeIfAbsent(op.getLeftId(), k -> new ArrayList<>()).add(op);
            return;
        }

        applyInsert(op);

        // If this node was deleted before it arrived, apply tombstone immediately
        if (pendingDeletes.remove(op.getNodeId())) {
            CRDTNode insertedNode = nodeIndex.get(op.getNodeId());
            if (insertedNode != null) {
                insertedNode.markDeleted();
            }
        }

        // This newly inserted node may unblock other buffered inserts
        replayWaitingOperations(op.getNodeId());
    }

    private void applyInsert(CRDTOperation op) {
        if (nodeIndex.containsKey(op.getNodeId())) {
            return;
        }

        CRDTNode node = new CRDTNode(op.getNodeId(), op.getCharValue(), op.getLeftId());

        int insertIndex = 0;

        if (op.getLeftId() != null) {
            int leftIndex = findNodeIndex(op.getLeftId());
            if (leftIndex < 0) {
                // Should be rare if buffering works correctly, but keep safe
                waitingOn.computeIfAbsent(op.getLeftId(), k -> new ArrayList<>()).add(op);
                return;
            }
            insertIndex = leftIndex + 1;
        }

        // Deterministic sibling ordering:
        // This runs for BOTH leftId == null and leftId != null.
        // That fixes concurrent inserts at document start too.
        while (insertIndex < nodes.size()) {
            CRDTNode next = nodes.get(insertIndex);

            // Only compare against nodes that share the same leftId
            if (!Objects.equals(next.getLeftId(), op.getLeftId())) {
                break;
            }

            // Deterministic tie-breaker by node ID
            if (compareNodeIds(next.getId(), op.getNodeId()) > 0) {
                break;
            }

            insertIndex++;
        }

        nodes.add(insertIndex, node);
        nodeIndex.put(node.getId(), node);
    }

    public synchronized void delete(CRDTOperation op) {
        if (!"DELETE".equals(op.getType())) {
            throw new IllegalArgumentException("Operation must be DELETE");
        }

        CRDTNode node = nodeIndex.get(op.getNodeId());
        if (node != null) {
            node.markDeleted();
        } else {
            // Node not present yet, remember to delete it later
            pendingDeletes.add(op.getNodeId());
        }
    }

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
                    continue;
                }

                if (pendingOp.getLeftId() == null || nodeIndex.containsKey(pendingOp.getLeftId())) {
                    applyInsert(pendingOp);

                    // Apply any pending delete for this node
                    if (pendingDeletes.remove(pendingOp.getNodeId())) {
                        CRDTNode insertedNode = nodeIndex.get(pendingOp.getNodeId());
                        if (insertedNode != null) {
                            insertedNode.markDeleted();
                        }
                    }

                    // This inserted node may unlock more operations
                    ready.add(pendingOp.getNodeId());
                } else {
                    // Still blocked; put it back under its missing dependency
                    waitingOn.computeIfAbsent(pendingOp.getLeftId(), k -> new ArrayList<>()).add(pendingOp);
                }
            }
        }
    }

    public String getText() {
        StringBuilder builder = new StringBuilder();
        for (CRDTNode node : nodes) {
            if (!node.isDeleted()) {
                builder.append(node.getValue());
            }
        }
        return builder.toString();
    }

    public List<CRDTNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public CRDTNode getNode(String nodeId) {
        return nodeIndex.get(nodeId);
    }

    private int findNodeIndex(String nodeId) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getId().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Compares node IDs in a way that is stable for Lamport-style IDs.
     *
     * Expected format examples:
     *   clientA:0000000012
     *   clientB:0000000007
     *
     * If the suffix is numeric, compare numerically.
     * If parsing fails, fall back to lexicographic comparison.
     */
    private int compareNodeIds(String idA, String idB) {
        if (Objects.equals(idA, idB)) {
            return 0;
        }

        try {
            String[] partsA = splitNodeId(idA);
            String[] partsB = splitNodeId(idB);

            int clientCmp = partsA[0].compareTo(partsB[0]);
            if (clientCmp != 0) {
                return clientCmp;
            }

            long clockA = Long.parseLong(partsA[1]);
            long clockB = Long.parseLong(partsB[1]);
            return Long.compare(clockA, clockB);
        } catch (Exception e) {
            return idA.compareTo(idB);
        }
    }

    private String[] splitNodeId(String id) {
        int idx = id.lastIndexOf(':');
        if (idx < 0 || idx == id.length() - 1) {
            return new String[]{id, "0"};
        }
        return new String[]{id.substring(0, idx), id.substring(idx + 1)};
    }
}