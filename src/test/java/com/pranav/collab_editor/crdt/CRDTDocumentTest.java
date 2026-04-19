package com.pranav.collab_editor.crdt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive unit tests for the CRDT module.
 *
 * Run with:
 *   mvn test -Dtest=CRDTDocumentTest
 *
 * Run with console output:
 *   mvn test -Dtest=CRDTDocumentTest -Dsurefire.useFile=false
 */
class CRDTDocumentTest {

    private CRDTDocument doc;

    @BeforeEach
    void setUp() {
        doc = new CRDTDocument();
    }

    // =========================================================================
    // GROUP 1 — Basic operations on a single document
    // =========================================================================

    @Nested
    @DisplayName("Basic insert and delete")
    class BasicOperations {

        @Test
        @DisplayName("Insert a single character into an empty document")
        void insertSingleCharIntoEmptyDocument() {
            doc.insert(CRDTOperation.insert("A:1", null, 'H', "A", 1));
            assertEquals("H", doc.getText());
            assertEquals(1, doc.getNodes().size());
        }

        @Test
        @DisplayName("Insert multiple characters in left-to-right sequence")
        void insertMultipleCharactersInSequence() {
            doc.insert(CRDTOperation.insert("A:1", null,  'H', "A", 1));
            doc.insert(CRDTOperation.insert("A:2", "A:1", 'e', "A", 2));
            doc.insert(CRDTOperation.insert("A:3", "A:2", 'y', "A", 3));
            assertEquals("Hey", doc.getText());
        }

        @Test
        @DisplayName("Insert at the start of an existing document (prepend)")
        void insertAtStart() {
            doc.insert(CRDTOperation.insert("A:1", null, 'B', "A", 1));
            doc.insert(CRDTOperation.insert("A:2", null, 'A', "A", 2));
            // Both have leftId=null — tiebreaker applies.
            // compareNodeIds("A:1", "A:2") < 0 → A:1 sorts first
            // So 'B' (A:1) is before 'A' (A:2) in this deterministic ordering.
            String text = doc.getText();
            assertEquals(2, text.length());
            assertTrue(text.contains("A") && text.contains("B"));
        }

        @Test
        @DisplayName("Delete a single character — getText() no longer shows it")
        void deleteASingleCharacter() {
            doc.insert(CRDTOperation.insert("A:1", null,  'H', "A", 1));
            doc.insert(CRDTOperation.insert("A:2", "A:1", 'i', "A", 2));
            doc.delete(CRDTOperation.delete("A:1", "A", 3));
            assertEquals("i", doc.getText());
        }

        @Test
        @DisplayName("getText() skips deleted (tombstoned) nodes")
        void getTextSkipsDeletedNodes() {
            doc.insert(CRDTOperation.insert("A:1", null,  'A', "A", 1));
            doc.insert(CRDTOperation.insert("A:2", "A:1", 'B', "A", 2));
            doc.insert(CRDTOperation.insert("A:3", "A:2", 'C', "A", 3));
            doc.delete(CRDTOperation.delete("A:2", "A", 4));

            assertEquals("AC", doc.getText());
            assertEquals(3, doc.getNodes().size()); // tombstone is still in the list
        }

        @Test
        @DisplayName("Insert then delete the same node — getText() shows empty")
        void insertThenDeleteSameNode() {
            doc.insert(CRDTOperation.insert("A:1", null, 'X', "A", 1));
            assertEquals("X", doc.getText());

            doc.delete(CRDTOperation.delete("A:1", "A", 2));
            assertEquals("", doc.getText());

            assertEquals(1, doc.getNodes().size()); // tombstone stays in list
            assertTrue(doc.getNode("A:1").isDeleted());
        }

        @Test
        @DisplayName("Duplicate insert (same op applied twice) is idempotent")
        void duplicateInsertIsIdempotent() {
            CRDTOperation op = CRDTOperation.insert("A:1", null, 'Z', "A", 1);
            doc.insert(op);
            doc.insert(op); // second apply must be a no-op

            assertEquals("Z", doc.getText());
            assertEquals(1, doc.getNodes().size());
        }

        @Test
        @DisplayName("Delete an already-deleted node is idempotent")
        void duplicateDeleteIsIdempotent() {
            doc.insert(CRDTOperation.insert("A:1", null, 'X', "A", 1));
            doc.delete(CRDTOperation.delete("A:1", "A", 2));
            doc.delete(CRDTOperation.delete("A:1", "A", 3)); // second delete — no-op

            assertEquals("", doc.getText());
            assertEquals(1, doc.getNodes().size());
        }
    }

    // =========================================================================
    // GROUP 2 — Two-client convergence
    // =========================================================================

    @Nested
    @DisplayName("Two-client convergence")
    class TwoClientConvergence {

        @Test
        @DisplayName("Concurrent inserts at document start (leftId = null) — converge")
        void concurrentInsertsAtStartConverge() {
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();

            CRDTOperation opA = CRDTOperation.insert("A:1", null, 'A', "A", 1);
            CRDTOperation opB = CRDTOperation.insert("B:1", null, 'B', "B", 1);

            c1.insert(opA); c1.insert(opB); // A first
            c2.insert(opB); c2.insert(opA); // B first

            assertEquals(c1.getText(), c2.getText(),
                "Both clients must produce identical text regardless of arrival order");
            assertEquals(2, c1.getText().length());
        }

        @Test
        @DisplayName("Concurrent inserts at same non-null position — converge")
        void concurrentInsertsAtSameNonNullPositionConverge() {
            // Both clients start with "AC" and concurrently insert between A and C
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();

            CRDTOperation baseA = CRDTOperation.insert("A:1", null,  'A', "A", 1);
            CRDTOperation baseC = CRDTOperation.insert("A:2", "A:1", 'C', "A", 2);

            c1.insert(baseA); c1.insert(baseC);
            c2.insert(baseA); c2.insert(baseC);

            // Both insert at leftId="A:1" (after 'A') concurrently
            CRDTOperation opB = CRDTOperation.insert("C1:3", "A:1", 'B', "C1", 3);
            CRDTOperation opX = CRDTOperation.insert("C2:3", "A:1", 'X', "C2", 3);

            c1.insert(opB); c1.insert(opX);
            c2.insert(opX); c2.insert(opB);

            assertEquals(c1.getText(), c2.getText());
            assertEquals(4, c1.getText().length());
        }

        @Test
        @DisplayName("Sequential pairs A→B and X→Y do not interleave — converge to ABXY")
        void sequentialPairsDoNotInterleave() {
            // ── The classic RGA interleaving bug ───────────────────────────────
            // C1 types "AB" (B is sequential after A).
            // C2 concurrently types "XY" (Y is sequential after X).
            // Both 'A' and 'X' are inserted at leftId=null (document start).
            //
            // WRONG (naive algorithm):
            //   c1 receives A,B then X,Y → inserts X between A and B → AXYB
            //   c2 receives X,Y then A,B → B goes right after A   → ABXY
            //   Result: DIVERGED (AXYB ≠ ABXY)
            //
            // CORRECT (subtree-aware algorithm):
            //   When inserting X, skip past B because B belongs to A's subtree
            //   (B.leftId=A, and A < X in sibling order). X goes AFTER B.
            //   Both clients converge to ABXY.
            // ──────────────────────────────────────────────────────────────────
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();

            CRDTOperation op1 = CRDTOperation.insert("C1:1", null,   'A', "C1", 1);
            CRDTOperation op2 = CRDTOperation.insert("C1:2", "C1:1", 'B', "C1", 2);
            CRDTOperation op3 = CRDTOperation.insert("C2:1", null,   'X', "C2", 1);
            CRDTOperation op4 = CRDTOperation.insert("C2:2", "C2:1", 'Y', "C2", 2);

            // c1: own ops first, then c2's ops
            c1.insert(op1); c1.insert(op2);
            c1.insert(op3); c1.insert(op4);

            // c2: own ops first, then c1's ops
            c2.insert(op3); c2.insert(op4);
            c2.insert(op1); c2.insert(op2);

            assertEquals(c1.getText(), c2.getText(),
                "Sequential pairs must not interleave — expected identical text on both clients");
            assertEquals("ABXY", c1.getText(),
                "A's subtree (AB) must stay together before X's subtree (XY)");
        }

        @Test
        @DisplayName("Client A deletes a char while client B inserts next to it — converge")
        void concurrentDeleteAndInsertConverge() {
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();

            CRDTOperation opH = CRDTOperation.insert("A:1", null,  'H', "A", 1);
            CRDTOperation opI = CRDTOperation.insert("A:2", "A:1", 'i', "A", 2);

            c1.insert(opH); c1.insert(opI);
            c2.insert(opH); c2.insert(opI);

            // C1 deletes 'H'; C2 inserts 'X' right after 'H' — concurrent
            CRDTOperation delH = CRDTOperation.delete("A:1", "C1", 3);
            // X has leftId=A:1. 'i' also has leftId=A:1 (both are siblings).
            // compareNodeIds("A:2", "C2:3"): "A" < "C2" → 'i' (A:2) comes before 'X' (C2:3)
            // So after deleting H the visible text is "iX" on both clients.
            CRDTOperation insX = CRDTOperation.insert("C2:3", "A:1", 'X', "C2", 3);

            c1.delete(delH); c1.insert(insX);
            c2.insert(insX); c2.delete(delH);

            assertEquals(c1.getText(), c2.getText(),
                "Both clients must converge to the same text");
            assertEquals("iX", c1.getText());
        }

        @Test
        @DisplayName("Ops arrive in reversed order — still converge")
        void opsArrivedInReverseOrderConverge() {
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();

            CRDTOperation op1 = CRDTOperation.insert("A:1", null,  'H', "A", 1);
            CRDTOperation op2 = CRDTOperation.insert("A:2", "A:1", 'e', "A", 2);
            CRDTOperation op3 = CRDTOperation.insert("A:3", "A:2", 'y', "A", 3);

            c1.insert(op1); c1.insert(op2); c1.insert(op3); // in order
            c2.insert(op3); c2.insert(op2); c2.insert(op1); // reversed

            assertEquals("Hey", c1.getText());
            assertEquals(c1.getText(), c2.getText());
        }

        @Test
        @DisplayName("Delete arrives before insert (pending delete) — does not crash")
        void deleteBeforeInsertHandledGracefully() {
            assertDoesNotThrow(() ->
                doc.delete(CRDTOperation.delete("A:1", "B", 2))
            );
            assertEquals("", doc.getText());

            // Insert arrives later — must be immediately tombstoned
            doc.insert(CRDTOperation.insert("A:1", null, 'Z', "A", 1));

            assertEquals("", doc.getText());        // deleted — not visible
            assertEquals(1, doc.getNodes().size()); // tombstone in list
            assertTrue(doc.getNode("A:1").isDeleted());
        }
    }

    // =========================================================================
    // GROUP 3 — Three clients
    // =========================================================================

    @Nested
    @DisplayName("Three-client convergence")
    class ThreeClientConvergence {

        @Test
        @DisplayName("Three clients insert at start in all different arrival orders — converge")
        void threeClientsAllOrdersConverge() {
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();
            CRDTDocument c3 = new CRDTDocument();

            CRDTOperation opA = CRDTOperation.insert("C1:1", null, 'A', "C1", 1);
            CRDTOperation opB = CRDTOperation.insert("C2:1", null, 'B', "C2", 1);
            CRDTOperation opC = CRDTOperation.insert("C3:1", null, 'C', "C3", 1);

            c1.insert(opA); c1.insert(opB); c1.insert(opC); // A, B, C
            c2.insert(opB); c2.insert(opC); c2.insert(opA); // B, C, A
            c3.insert(opC); c3.insert(opA); c3.insert(opB); // C, A, B

            assertEquals(c1.getText(), c2.getText());
            assertEquals(c2.getText(), c3.getText());
            assertEquals(3, c1.getText().length());
        }

        @Test
        @DisplayName("Three clients: mix of inserts and deletes — converge")
        void threeClientsMixedOpsConverge() {
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();
            CRDTDocument c3 = new CRDTDocument();

            CRDTOperation[] base = {
                CRDTOperation.insert("A:1", null,  'H', "A", 1),
                CRDTOperation.insert("A:2", "A:1", 'e', "A", 2),
                CRDTOperation.insert("A:3", "A:2", 'l', "A", 3),
                CRDTOperation.insert("A:4", "A:3", 'l', "A", 4),
                CRDTOperation.insert("A:5", "A:4", 'o', "A", 5),
            };
            for (CRDTDocument c : new CRDTDocument[]{c1, c2, c3}) {
                for (CRDTOperation op : base) c.insert(op);
            }

            CRDTOperation delH    = CRDTOperation.delete("A:1", "C1", 6);
            CRDTOperation insExcl = CRDTOperation.insert("C2:6", "A:5", '!', "C2", 6);
            CRDTOperation delO    = CRDTOperation.delete("A:5", "C3", 6);

            c1.delete(delH);    c1.insert(insExcl); c1.delete(delO);
            c2.delete(delO);    c2.delete(delH);    c2.insert(insExcl);
            c3.insert(insExcl); c3.delete(delO);    c3.delete(delH);

            assertEquals(c1.getText(), c2.getText());
            assertEquals(c2.getText(), c3.getText());
            assertEquals("ell!", c1.getText());
        }
    }

    // =========================================================================
    // GROUP 4 — Out-of-order delivery and buffering
    // =========================================================================

    @Nested
    @DisplayName("Out-of-order delivery and buffering")
    class BufferingEdgeCases {

        @Test
        @DisplayName("Cascading dependency chain — C waits for B, B waits for A")
        void cascadingDependenciesResolveCorrectly() {
            CRDTOperation opA = CRDTOperation.insert("A:1", null,  'A', "A", 1);
            CRDTOperation opB = CRDTOperation.insert("A:2", "A:1", 'B', "A", 2);
            CRDTOperation opC = CRDTOperation.insert("A:3", "A:2", 'C', "A", 3);

            doc.insert(opC); // buffered (A:2 missing)
            assertEquals("", doc.getText());

            doc.insert(opB); // buffered (A:1 missing)
            assertEquals("", doc.getText());

            doc.insert(opA); // unblocks B, which unblocks C
            assertEquals("ABC", doc.getText());
        }

        @Test
        @DisplayName("Delete arrives for a buffered insert — applied on arrival")
        void deleteArrivesForBufferedInsert() {
            CRDTOperation opA  = CRDTOperation.insert("A:1", null,  'A', "A", 1);
            CRDTOperation opB  = CRDTOperation.insert("A:2", "A:1", 'B', "A", 2);
            CRDTOperation delB = CRDTOperation.delete("A:2", "B", 3);

            doc.insert(opB);  // buffered — A:1 missing
            doc.delete(delB); // pendingDeletes gets A:2

            // When A arrives and unblocks B, the pending delete must fire immediately
            doc.insert(opA);

            assertEquals("A", doc.getText()); // 'B' was pre-deleted
        }

        @Test
        @DisplayName("Empty document — delete and getText() do not crash")
        void emptyDocumentOperationsDoNotCrash() {
            assertEquals("", doc.getText());
            assertEquals(0, doc.getNodes().size());
            assertDoesNotThrow(() ->
                doc.delete(CRDTOperation.delete("ghost:1", "A", 1))
            );
            assertEquals("", doc.getText());
        }
    }

    // =========================================================================
    // GROUP 5 — Undo / redo simulation
    // =========================================================================

    @Nested
    @DisplayName("Undo / redo simulation")
    class UndoRedoSimulation {

        @Test
        @DisplayName("Undo an insert by sending a delete (inverse op)")
        void undoInsertBySendingDelete() {
            doc.insert(CRDTOperation.insert("A:1", null,  'H', "A", 1));
            doc.insert(CRDTOperation.insert("A:2", "A:1", 'i', "A", 2));
            assertEquals("Hi", doc.getText());

            doc.delete(CRDTOperation.delete("A:2", "A", 3));
            assertEquals("H", doc.getText());
        }

        @Test
        @DisplayName("Redo after undo — re-insert with a new node ID")
        void redoAfterUndo() {
            // Note: because CRDTNode tombstones are one-way (no markRestored()),
            // redo is implemented by creating a NEW insert op with a new node ID.
            // Re-sending the original op is a no-op due to the idempotency guard.
            doc.insert(CRDTOperation.insert("A:1", null,  'H', "A", 1));
            doc.insert(CRDTOperation.insert("A:2", "A:1", 'i', "A", 2));

            // Undo 'i'
            doc.delete(CRDTOperation.delete("A:2", "A", 3));
            assertEquals("H", doc.getText());

            // Redo: new insert at the same position with a new node ID
            doc.insert(CRDTOperation.insert("A:4", "A:1", 'i', "A", 4));
            assertEquals("Hi", doc.getText());
        }

        @Test
        @DisplayName("Undo/redo convergence across two clients")
        void undoRedoConvergesAcrossClients() {
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();

            CRDTOperation ins  = CRDTOperation.insert("A:1", null, 'X', "A", 1);
            CRDTOperation undo = CRDTOperation.delete("A:1", "A", 2);

            c1.insert(ins); c2.insert(ins);
            c1.delete(undo); c2.delete(undo);

            assertEquals(c1.getText(), c2.getText());
            assertEquals("", c1.getText());
        }
    }

    // =========================================================================
    // GROUP 6 — Lamport clock / compareNodeIds
    // =========================================================================

    @Nested
    @DisplayName("Node ID comparison (Lamport clock correctness)")
    class NodeIdComparison {

        @Test
        @DisplayName("compareNodeIds respects numeric clock order, not lexicographic")
        void compareNodeIdsUsesNumericClock() {
            // Lexicographically "A:9" > "A:10" because '9' > '1'.
            // Numerically "A:9" < "A:10" — this is what compareNodeIds must return.
            assertTrue(CRDTDocument.compareNodeIds("A:9", "A:10") < 0,
                "A:9 must sort before A:10 (numeric, not lexicographic)");
        }

        @Test
        @DisplayName("compareNodeIds: same clock, different clients — ordered by clientId")
        void compareNodeIdsSameClockDifferentClients() {
            assertTrue(CRDTDocument.compareNodeIds("Alice:5", "Bob:5") < 0,
                "Alice:5 must sort before Bob:5");
        }

        @Test
        @DisplayName("Concurrent inserts with clock > 9 converge (regression for lexicographic bug)")
        void concurrentInsertsWithHighClockConverge() {
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();

            CRDTOperation op9  = CRDTOperation.insert("A:9",  null, 'X', "A", 9);
            CRDTOperation op10 = CRDTOperation.insert("A:10", null, 'Y', "A", 10);

            c1.insert(op9);  c1.insert(op10);
            c2.insert(op10); c2.insert(op9);

            assertEquals(c1.getText(), c2.getText(),
                "Must converge even when clock values cross a digit boundary (9 vs 10)");
        }
    }

    // =========================================================================
    // GROUP 7 — Realistic editing sessions
    // =========================================================================

    @Nested
    @DisplayName("Realistic editing scenarios")
    class RealisticEditingScenarios {

        @Test
        @DisplayName("Type 'Hello World', delete 'World' — leaves 'Hello '")
        void typeAndDeleteWord() {
            String text = "Hello World";
            String prev = null;
            for (int i = 0; i < text.length(); i++) {
                String nodeId = "A:" + (i + 1);
                doc.insert(CRDTOperation.insert(nodeId, prev, text.charAt(i), "A", i + 1));
                prev = nodeId;
            }
            assertEquals("Hello World", doc.getText());

            for (int i = 7; i <= 11; i++) {
                doc.delete(CRDTOperation.delete("A:" + i, "A", i + 100));
            }
            assertEquals("Hello ", doc.getText());
        }

        @Test
        @DisplayName("Two clients collaboratively build 'Hello World' — converge")
        void twoClientsBuildSentence() {
            CRDTDocument c1 = new CRDTDocument();
            CRDTDocument c2 = new CRDTDocument();

            String c1text = "Hello";
            String prev = null;
            for (int i = 0; i < c1text.length(); i++) {
                String nid = "C1:" + (i + 1);
                CRDTOperation op = CRDTOperation.insert(nid, prev, c1text.charAt(i), "C1", i + 1);
                c1.insert(op); c2.insert(op);
                prev = nid;
            }
            String lastC1 = prev;

            String c2text = " World";
            prev = lastC1;
            for (int i = 0; i < c2text.length(); i++) {
                String nid = "C2:" + (i + 1);
                CRDTOperation op = CRDTOperation.insert(nid, prev, c2text.charAt(i), "C2", i + 1);
                c1.insert(op); c2.insert(op);
                prev = nid;
            }

            assertEquals("Hello World", c1.getText());
            assertEquals(c1.getText(), c2.getText());
        }
    }

    // =========================================================================
    // GROUP 8 — Performance
    // =========================================================================

    @Nested
    @DisplayName("Performance")
    class Performance {

        @Test
        @DisplayName("10,000 sequential appends complete in under 2 seconds")
        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        void tenThousandSequentialAppends() {
            String prev = null;
            for (int i = 1; i <= 10_000; i++) {
                String nodeId = "A:" + i;
                doc.insert(CRDTOperation.insert(nodeId, prev, 'a', "A", i));
                prev = nodeId;
            }
            assertEquals(10_000, doc.getText().length());
            assertEquals(10_000, doc.getNodes().size());
        }

        @Test
        @DisplayName("10,000 out-of-order inserts (fully reversed) complete in under 5 seconds")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void tenThousandReverseOrderInserts() {
            CRDTOperation[] ops = new CRDTOperation[10_000];
            String prev = null;
            for (int i = 0; i < 10_000; i++) {
                String nodeId = "A:" + (i + 1);
                ops[i] = CRDTOperation.insert(nodeId, prev, 'x', "A", i + 1);
                prev = nodeId;
            }
            // Apply in reverse — maximum buffering stress test
            for (int i = ops.length - 1; i >= 0; i--) {
                doc.insert(ops[i]);
            }
            assertEquals(10_000, doc.getText().length());
        }
    }
}