package com.pranav.collab_editor.crdt;

/**
 * A single character node in the CRDT document.
 * Nodes are never physically removed; a delete is represented by a tombstone flag.
 */
public class CRDTNode {

    private final String id;
    private final char value;
    private final String leftId;
    private boolean deleted;

    public CRDTNode(String id, char value, String leftId) {
        this.id = id;
        this.value = value;
        this.leftId = leftId;
        this.deleted = false;
    }

    public String getId() {
        return id;
    }

    public char getValue() {
        return value;
    }

    public String getLeftId() {
        return leftId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void markDeleted() {
        this.deleted = true;
    }

    @Override
    public String toString() {
        return "CRDTNode{" +
                "id='" + id + '\'' +
                ", value=" + value +
                ", leftId='" + leftId + '\'' +
                ", deleted=" + deleted +
                '}';
    }
}