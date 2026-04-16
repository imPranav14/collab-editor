package com.pranav.collab_editor.controller;

import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @GetMapping
    public ResponseEntity<List<Document>> listDocuments(Principal principal) {
        List<Document> documents = documentService.getUserDocuments(principal.getName());
        return ResponseEntity.ok(documents);
    }

    @PostMapping
    public ResponseEntity<Document> createDocument(@Valid @RequestBody DocumentService.CreateDocumentRequest request,
                                                   Principal principal) {
        Document doc = documentService.createDocument(principal.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(doc);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(@PathVariable String id, Principal principal) {
        Optional<Document> documentOpt = documentService.getDocument(principal.getName(), id);
        if (documentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(documentOpt.get());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String id, Principal principal) {
        boolean deleted = documentService.deleteDocument(principal.getName(), id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
