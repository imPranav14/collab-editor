package com.pranav.collab_editor.controller;

import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.model.User;
import com.pranav.collab_editor.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @GetMapping
    public ResponseEntity<List<Document>> listDocuments(@AuthenticationPrincipal User user) {
        List<Document> documents = documentService.getUserDocuments(user);
        return ResponseEntity.ok(documents);
    }

    @PostMapping
    public ResponseEntity<Document> createDocument(@Valid @RequestBody DocumentService.CreateDocumentRequest request,
                                                   @AuthenticationPrincipal User user) {
        Document doc = documentService.createDocument(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(doc);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(@PathVariable String id, @AuthenticationPrincipal User user) {
        Optional<Document> documentOpt = documentService.getDocument(user, id);
        if (documentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(documentOpt.get());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String id, @AuthenticationPrincipal User user) {
        boolean deleted = documentService.deleteDocument(user, id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
