package com.pranav.collab_editor.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * JPA entity representing a single CRDT operation in the operation log.
 *
 * This is the core of the persistence strategy. Instead of storing the
 * document as a blob of text, we store every individual INSERT and DELETE
 * operation ever applied to it (event sourcing pattern).
 *
 * Why event sourcing?
 *   - Full edit history for free — who typed what and when
 *   - Current state is always reconstructible by replaying ops
 *   - Nothing is truly lost — a "delete" is a tombstone op, not erasure
 *   - Works naturally with the CRDT model which is already op-based
 *
 * Table: operations
 * Primary query pattern: "all ops for document X in logical time order"
 * → covered by the composite index on (document_id, lamport_clock)
 */
@Entity
@Table(
    name = "operations",
    indexes = {
        @Index(name = "idx_ops_document_clock", columnList = "document_id, lamport_clock")
    }
)
public class Operation {

    /**
     * Auto-incrementing surrogate PK — PostgreSQL handles the sequence.
     * Used in Phase 9 (snapshots) to identify which ops are already
     * captured in a snapshot vs. which still need to be replayed.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The document this operation belongs to.
     * In Phase 2 this will become a @ManyToOne reference to the Document entity.
     * Kept as a plain String here so Phase 5 is self-contained.
     */
    @Column(name = "document_id", nullable = false)
    private String documentId;

    /**
     * Type of operation: "INSERT" or "DELETE".
     * Plain String (not enum) keeps the DB schema simple and readable.
     */
    @Column(name = "op_type", nullable = false, length = 10)
    private String opType;

    /**
     * Globally unique CRDT node ID targeted by this operation.
     * Format: "clientId:lamportClock"  e.g. "userA:42"
     *
     * INSERT → ID of the node being created.
     * DELETE → ID of the node being tombstoned.
     */
    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    /**
     * ID of the left-neighbour node at insertion time.
     * Null when inserting at document start.
     * Always null for DELETE operations.
     */
    @Column(name = "left_id", length = 100)
    private String leftId;

    /**
     * The character being inserted.
     * Null for DELETE operations.
     */
    @Column(name = "char_value", length = 1)
    private Character charValue;

    /**
     * Client (user session) that generated this operation.
     * Together with lamportClock forms the unique nodeId.
     */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /**
     * Lamport logical clock value when this op was generated.
     *
     * Used for:
     *   1. Replay ordering — ops replayed in lamportClock ASC order
     *   2. Part of the nodeId
     *   3. Phase 9 snapshots — "replay only ops with id > snapshot.lastOpId"
     */
    @Column(name = "lamport_clock", nullable = false)
    private long lamportClock;

    /**
     * Wall-clock time the server persisted this op.
     * Not used for CRDT ordering (Lamport clock handles that).
     * Useful for audit trails and "last edited X ago" UI.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    public Operation() { /* Required by JPA */ }

    public Operation(String documentId, String opType, String nodeId, String leftId,
                     Character charValue, String clientId, long lamportClock) {
        this.documentId   = documentId;
        this.opType       = opType;
        this.nodeId       = nodeId;
        this.leftId       = leftId;
        this.charValue    = charValue;
        this.clientId     = clientId;
        this.lamportClock = lamportClock;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters and Setters
    // ─────────────────────────────────────────────────────────────────────────

    public Long getId()                   { return id; }
    public void setId(Long id)            { this.id = id; }

    public String getDocumentId()         { return documentId; }
    public void setDocumentId(String d)   { this.documentId = d; }

    public String getOpType()             { return opType; }
    public void setOpType(String t)       { this.opType = t; }

    public String getNodeId()             { return nodeId; }
    public void setNodeId(String n)       { this.nodeId = n; }

    public String getLeftId()             { return leftId; }
    public void setLeftId(String l)       { this.leftId = l; }

    public Character getCharValue()       { return charValue; }
    public void setCharValue(Character c) { this.charValue = c; }

    public String getClientId()           { return clientId; }
    public void setClientId(String c)     { this.clientId = c; }

    public long getLamportClock()         { return lamportClock; }
    public void setLamportClock(long lc)  { this.lamportClock = lc; }

    public LocalDateTime getCreatedAt()   { return createdAt; }

    @Override
    public String toString() {
        return "Operation{id=" + id + ", docId='" + documentId + "', type='" + opType +
               "', nodeId='" + nodeId + "', clock=" + lamportClock + '}';
    }
}