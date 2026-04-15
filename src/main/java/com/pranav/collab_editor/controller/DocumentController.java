package com.pranav.collab_editor.controller;

import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.model.User;
import com.pranav.collab_editor.repository.DocumentRepository;
import com.pranav.collab_editor.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> listDocuments(Principal principal) {
        Optional<User> userOpt = userRepository.findByUsername(principal.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<DocumentResponse> documents = documentRepository.findByOwner(userOpt.get()).stream()
                .map(doc -> new DocumentResponse(doc.getId(), doc.getTitle()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(documents);
    }

    @PostMapping
    public ResponseEntity<DocumentResponse> createDocument(@RequestBody CreateDocumentRequest request,
                                                           Principal principal) {
        Optional<User> userOpt = userRepository.findByUsername(principal.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Document doc = new Document();
        doc.setTitle(request.title() == null || request.title().isBlank() ? "Untitled" : request.title());
        doc.setOwner(userOpt.get());
        documentRepository.save(doc);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new DocumentResponse(doc.getId(), doc.getTitle()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> getDocument(@PathVariable String id, Principal principal) {
        Optional<User> userOpt = userRepository.findByUsername(principal.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Document> documentOpt = documentRepository.findById(id);
        if (documentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Document document = documentOpt.get();
        if (!document.getOwner().getId().equals(userOpt.get().getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(new DocumentResponse(document.getId(), document.getTitle()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String id, Principal principal) {
        Optional<User> userOpt = userRepository.findByUsername(principal.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Document> documentOpt = documentRepository.findById(id);
        if (documentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Document document = documentOpt.get();
        if (!document.getOwner().getId().equals(userOpt.get().getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        documentRepository.delete(document);
        return ResponseEntity.noContent().build();
    }

    public record CreateDocumentRequest(String title) {}
    public record DocumentResponse(String id, String title) {}
}
