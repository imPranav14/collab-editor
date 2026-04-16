package com.pranav.collab_editor.service;

import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.model.User;
import com.pranav.collab_editor.repository.DocumentRepository;
import com.pranav.collab_editor.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DocumentService {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    public List<Document> getUserDocuments(String username) {
        User user = getUserByUsername(username);
        return documentRepository.findByOwner(user);
    }

    public Document createDocument(String username, @Valid CreateDocumentRequest request) {
        User user = getUserByUsername(username);

        Document doc = new Document();
        doc.setTitle(request.title() == null || request.title().isBlank() ? "Untitled" : request.title());
        doc.setOwner(user);
        return documentRepository.save(doc);
    }

    public Optional<Document> getDocument(String username, String id) {
        User user = getUserByUsername(username);
        return documentRepository.findByIdAndOwner(id, user);
    }

    public boolean deleteDocument(String username, String id) {
        User user = getUserByUsername(username);
        Optional<Document> documentOpt = documentRepository.findByIdAndOwner(id, user);
        if (documentOpt.isPresent()) {
            documentRepository.delete(documentOpt.get());
            return true;
        }
        return false;
    }

    private User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public record CreateDocumentRequest(@NotBlank(message = "Title is required") String title) {}
}