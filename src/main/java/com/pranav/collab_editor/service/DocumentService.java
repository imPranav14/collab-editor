package com.pranav.collab_editor.service;

import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.model.User;
import com.pranav.collab_editor.repository.DocumentRepository;
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

    public List<Document> getUserDocuments(User user) {
        return documentRepository.findByOwner(user);
    }

    public Document createDocument(User user, @Valid CreateDocumentRequest request) {
        Document doc = new Document();
        doc.setTitle(request.title() == null || request.title().isBlank() ? "Untitled" : request.title());
        doc.setOwner(user);
        return documentRepository.save(doc);
    }

    public Optional<Document> getDocument(User user, String id) {
        return documentRepository.findByIdAndOwner(id, user);
    }

    public boolean deleteDocument(User user, String id) {
        Optional<Document> documentOpt = documentRepository.findByIdAndOwner(id, user);
        if (documentOpt.isPresent()) {
            documentRepository.delete(documentOpt.get());
            return true;
        }
        return false;
    }

    public record CreateDocumentRequest(@NotBlank(message = "Title is required") String title) {}
}