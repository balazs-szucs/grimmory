package org.booklore.service;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.dto.request.AuthorUpdateRequest;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.AuthorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthorPersistenceService {

    private final AuthorRepository authorRepository;

    public Optional<AuthorEntity> findById(Long authorId) {
        return authorRepository.findById(authorId);
    }

    @Transactional
    public AuthorEntity applyMetadataAndSave(Long authorId, AuthorSearchResult result) {
        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(authorId));
        if (!author.isDescriptionLocked()) {
            author.setDescription(result.getDescription());
        }
        if (!author.isAsinLocked()) {
            author.setAsin(result.getAsin());
        }
        return authorRepository.save(author);
    }

    @Transactional
    public AuthorEntity unmatch(Long authorId) {
        AuthorEntity author = authorRepository.findById(authorId).orElse(null);
        if (author == null) return null;
        author.setDescription(null);
        author.setAsin(null);
        return authorRepository.save(author);
    }

    @Transactional
    public String deleteAuthor(Long authorId) {
        AuthorEntity author = authorRepository.findById(authorId).orElse(null);
        if (author == null) return null;
        String authorName = author.getName();
        if (author.getBookMetadataEntityList() != null) {
            for (BookMetadataEntity metadata : author.getBookMetadataEntityList()) {
                metadata.getAuthors().remove(author);
            }
        }
        authorRepository.delete(author);
        return authorName;
    }

    @Transactional
    public AuthorEntity update(Long authorId, AuthorUpdateRequest request) {
        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(authorId));
        if (request.getName() != null) {
            author.setName(request.getName());
        }
        if (request.getDescription() != null) {
            author.setDescription(request.getDescription().isBlank() ? null : request.getDescription());
        }
        if (request.getAsin() != null) {
            author.setAsin(request.getAsin().isBlank() ? null : request.getAsin());
        }
        if (request.getNameLocked() != null) {
            author.setNameLocked(request.getNameLocked());
        }
        if (request.getDescriptionLocked() != null) {
            author.setDescriptionLocked(request.getDescriptionLocked());
        }
        if (request.getAsinLocked() != null) {
            author.setAsinLocked(request.getAsinLocked());
        }
        if (request.getPhotoLocked() != null) {
            author.setPhotoLocked(request.getPhotoLocked());
        }
        return authorRepository.save(author);
    }
}
