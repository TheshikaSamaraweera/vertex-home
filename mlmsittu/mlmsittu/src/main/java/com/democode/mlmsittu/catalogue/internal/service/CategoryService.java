package com.democode.mlmsittu.catalogue.internal.service;

import com.democode.mlmsittu.catalogue.internal.domain.Category;
import com.democode.mlmsittu.catalogue.internal.repo.CategoryRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final ItemCodeAllocator codes;

    public CategoryService(CategoryRepository categories, ItemCodeAllocator codes) {
        this.codes = codes;
        this.categories = categories;
    }

    @Transactional
    @Audited(action = "CATEGORY_CREATED", entityType = "category", auditFailures = true)
    public Category create(String code, String name) {
        Category category = new Category();
        category.setCode(code.trim().toUpperCase(Locale.ROOT));
        category.setName(name.trim());
        // Every item in this category will carry these two letters. Decided here because this is
        // the only moment a clash with an existing category can be resolved.
        category.setCodePrefix(codes.allocatePrefix(name));

        try {
            Category saved = categories.saveAndFlush(category);
            AuditContext.record(
                    saved.getId(), null, Map.of("code", saved.getCode(), "name", saved.getName()));
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.ifConstraintIs(
                    e,
                    "category_code_key",
                    "DUPLICATE_CATEGORY_CODE",
                    "A category with that code already exists.");
        }
    }

    @Transactional(readOnly = true)
    public List<Category> list() {
        return categories.findAllOrdered();
    }

    @Transactional(readOnly = true)
    public Category get(UUID id) {
        return categories
                .findById(id)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "CATEGORY_NOT_FOUND", "No category with that id."));
    }
}
