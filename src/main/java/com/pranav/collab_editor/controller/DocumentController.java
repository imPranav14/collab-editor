package com.pranav.collab_editor.controller;

import com.pranav.collab_editor.dto.DocumentLoadResponse;
import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.model.Operation;
import com.pranav.collab_editor.model.User;
import com.pranav.collab_editor.service.CRDTService;
import com.pranav.collab_editor.service.DocumentService;
import com.pranav.collab_editor.service.OperationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST controller for all document operations.
 *
 * Phase 2 endpoints — document CRUD :
 *   GET    /api/documents           → list documents for the logged-in user
 *   POST   /api/documents           → create a new document
 *   GET    /api/documents/{id}      → get document metadata (title, owner, dates)
 *   DELETE /api/documents/{id}      → delete a document
 *
 * Phase 5 endpoints — operation log and document state:
 *   GET    /api/documents/{id}/load → replay op log, return full state to client
 *   GET    /api/documents/{id}/text → debug: current server-side text
 *
 * Why two separate GET endpoints for the same document?
 *
 *   GET /api/documents/{id}
 *     Returns the Document entity — title, owner, timestamps.
 *     Lightweight. Used for listing, document headers, access checks.
 *     Does NOT include operation history.
 *
 *   GET /api/documents/{id}/load
 *     Returns the full operation log + current text.
 *     Heavier — triggers a full op-log replay on the server.
 *     Called once by the client when it first opens the editor.
 *     After this the client stays in sync via WebSocket.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    // ── Phase 2 dependency ────────────────────────────────────────────────────
    @Autowired
    private DocumentService documentService;

    // ── Phase 5 dependencies ──────────────────────────────────────────────────
    @Autowired
    private OperationService operationService;

    @Autowired
    private CRDTService crdtService;

    // =========================================================================
    // Phase 2 — Document CRUD 
    // =========================================================================

    /**
     * Lists all documents owned by or shared with the logged-in user.
     *
     * GET /api/documents
     */
    @GetMapping
    public ResponseEntity<List<Document>> listDocuments(@AuthenticationPrincipal User user) {
        List<Document> documents = documentService.getUserDocuments(user);
        return ResponseEntity.ok(documents);
    }

    /**
     * Creates a new empty document for the logged-in user.
     *
     * POST /api/documents
     * Body: { "title": "My New Doc" }
     */
    @PostMapping
    public ResponseEntity<Document> createDocument(
            @Valid @RequestBody DocumentService.CreateDocumentRequest request,
            @AuthenticationPrincipal User user) {
        Document doc = documentService.createDocument(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(doc);
    }

    /**
     * Returns document metadata — title, owner, timestamps.
     * Does NOT include the operation log or document text.
     * Use /api/documents/{id}/load for the full editor state.
     *
     * GET /api/documents/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(
            @PathVariable String id,
            @AuthenticationPrincipal User user) {
        Optional<Document> documentOpt = documentService.getDocument(user, id);
        if (documentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(documentOpt.get());
    }

    /**
     * Deletes a document and all its associated operations.
     * Only the document owner can delete it.
     *
     * DELETE /api/documents/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String id,
            @AuthenticationPrincipal User user) {
        boolean deleted = documentService.deleteDocument(user, id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Phase 5 — Operation log and document state
    // =========================================================================

    /**
     * Loads the full document state for the editor.
     *
     * Called by the client ONCE when the user opens a document, before
     * connecting via WebSocket. Returns everything needed to reconstruct
     * the current document state in the browser:
     *   - The ordered list of all CRDT operations (INSERT and DELETE)
     *   - The current text as computed by the server (sanity check)
     *   - Document metadata (title, id)
     *
     * Client workflow:
     *   1. Call this endpoint → receive op list
     *   2. Replay all ops on the client-side JS CRDT → render document
     *   3. Connect WebSocket → subscribe to live ops going forward
     *
     * Also replays the op log server-side and caches the CRDTDocument
     * in CRDTService so subsequent WebSocket ops apply immediately.
     *
     * Returns 404 if the document doesn't exist or the user has no access.
     *
     * GET /api/documents/{id}/load
     */
    @GetMapping("/{id}/load")
    public ResponseEntity<DocumentLoadResponse> loadDocument(
            @PathVariable String id,
            @AuthenticationPrincipal User user) {

        // Access check — reuse Phase 2's service to confirm the user can see this doc
        Optional<Document> documentOpt = documentService.getDocument(user, id);
        if (documentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Document document = documentOpt.get();
        log.info("Loading document id={} title='{}' for user={}",
                id, document.getTitle(), user.getUsername());

        // Replay the full operation log on the server and cache the result
        operationService.replayAll(id, crdtService);

        // Fetch the raw op list to send to the client for its own replay
        List<Operation> ops = operationService.findAllForDocument(id);

        // Current text from the just-replayed server-side CRDT
        String currentText = crdtService.getText(id);

        log.info("Document loaded: id={} ops={} textLength={}",
                id, ops.size(), currentText.length());

        DocumentLoadResponse response = new DocumentLoadResponse(
                document.getId(),
                document.getTitle(),
                ops,
                currentText
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Debug/test endpoint — returns the current server-side text of a document.
     *
     * Does NOT trigger a replay. Returns whatever is currently in the
     * in-memory CRDTService cache. Useful during Phase 5 testing to verify
     * ops are being applied correctly without inspecting the database.
     *
     * Example: GET /api/documents/test-doc/text → "Hello"
     *
     * GET /api/documents/{id}/text
     */
    @GetMapping("/{id}/text")
    public ResponseEntity<String> getDocumentText(
            @PathVariable String id,
            @AuthenticationPrincipal User user) {

        // Lightweight access check
        Optional<Document> documentOpt = documentService.getDocument(user, id);
        if (documentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String text = crdtService.getText(id);
        log.debug("Text query for doc={}: '{}'", id, text);
        return ResponseEntity.ok(text);
    }
}