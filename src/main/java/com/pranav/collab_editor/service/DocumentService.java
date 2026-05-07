package com.pranav.collab_editor.service;

import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.model.DocumentShare;
import com.pranav.collab_editor.model.User;
import com.pranav.collab_editor.repository.DocumentRepository;
import com.pranav.collab_editor.repository.DocumentShareRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Optional;

@Service
@Validated
public class DocumentService {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentShareRepository documentShareRepository;

    public List<Document> getUserDocuments(User user) {
        return documentRepository.findDocumentsAccessibleByUser(user, user.getId());
    }

    public Document createDocument(User user, @Valid CreateDocumentRequest request) {
        Document doc = new Document();
        doc.setTitle(request.title() == null || request.title().isBlank() ? "Untitled" : request.title());
        doc.setOwner(user);
        return documentRepository.save(doc);
    }

    public Optional<Document> getDocument(User user, String id) {
        if (documentRepository.hasUserAccessToDocument(id, user.getId())) {
            return documentRepository.findById(id);
        }
        return Optional.empty();
    }

    public boolean deleteDocument(User user, String id) {
        Optional<Document> documentOpt = documentRepository.findByIdAndOwner(id, user);
        if (documentOpt.isPresent()) {
            documentRepository.delete(documentOpt.get());
            return true;
        }
        return false;
    }

    // Document sharing methods
    public DocumentShare shareDocument(User owner, String documentId, String targetUserId, DocumentShare.Permission permission) {
        Optional<Document> documentOpt = documentRepository.findByIdAndOwner(documentId, owner);
        if (documentOpt.isEmpty()) {
            throw new IllegalArgumentException("Document not found or not owned by user");
        }

        // Check if already shared
        if (documentShareRepository.existsByDocumentAndSharedWithUser(documentOpt.get(),
            new User() {{ setId(targetUserId); }})) {
            throw new IllegalArgumentException("Document already shared with this user");
        }

        DocumentShare share = new DocumentShare();
        share.setDocument(documentOpt.get());
        share.setSharedWithUser(new User() {{ setId(targetUserId); }});
        share.setSharedByUser(owner);
        share.setPermission(permission);

        return documentShareRepository.save(share);
    }

    public List<DocumentShare> getDocumentShares(String documentId, User owner) {
        Optional<Document> documentOpt = documentRepository.findByIdAndOwner(documentId, owner);
        if (documentOpt.isEmpty()) {
            throw new IllegalArgumentException("Document not found or not owned by user");
        }
        return documentShareRepository.findByDocument(documentOpt.get());
    }

    public void revokeShare(User owner, String documentId, String targetUserId) {
        Optional<Document> documentOpt = documentRepository.findByIdAndOwner(documentId, owner);
        if (documentOpt.isEmpty()) {
            throw new IllegalArgumentException("Document not found or not owned by user");
        }

        Optional<DocumentShare> shareOpt = documentShareRepository.findByDocumentAndSharedWithUser(
            documentOpt.get(), new User() {{ setId(targetUserId); }});
        if (shareOpt.isPresent()) {
            documentShareRepository.delete(shareOpt.get());
        }
    }

    public record CreateDocumentRequest(@NotBlank(message = "Title is required") String title) {}
    public record ShareDocumentRequest(@NotBlank String targetUserId, DocumentShare.Permission permission) {}
}