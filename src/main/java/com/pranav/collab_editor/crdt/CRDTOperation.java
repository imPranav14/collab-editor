package com.pranav.collab_editor.crdt;

public class CRDTOperation {

    private final String type;
    private final String nodeId;
    private final String leftId;
    private final Character charValue;
    private final String clientId;
    private final long lamportClock;

    public CRDTOperation(String type, String nodeId, String leftId, Character charValue, String clientId, long lamportClock) {
        this.type = type;
        this.nodeId = nodeId;
        this.leftId = leftId;
        this.charValue = charValue;
        this.clientId = clientId;
        this.lamportClock = lamportClock;
    }

    public static CRDTOperation insert(String nodeId, String leftId, char charValue, String clientId, long lamportClock) {
        return new CRDTOperation("INSERT", nodeId, leftId, charValue, clientId, lamportClock);
    }

    public static CRDTOperation delete(String nodeId, String clientId, long lamportClock) {
        return new CRDTOperation("DELETE", nodeId, null, null, clientId, lamportClock);
    }

    public String getType() {
        return type;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getLeftId() {
        return leftId;
    }

    public Character getCharValue() {
        return charValue;
    }

    public String getClientId() {
        return clientId;
    }

    public long getLamportClock() {
        return lamportClock;
    }
}