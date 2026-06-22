package com.nextplease.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record JobCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank String description,
        @NotBlank @Size(max = 50) String jobType,
        @NotBlank @Size(max = 50) String category,
        @NotBlank @Size(max = 50) String specialty,
        BigDecimal compensation,
        @NotNull @Min(0) @Max(100) Integer minReqRs,
        @Size(max = 200) String location,
        Boolean isRemote,
        @Min(1) Integer capacity,
        OffsetDateTime deadlineAt,
        @Valid List<SkillRequirement> skills,
        @Valid List<FormField> formFields
) {
    public record SkillRequirement(
            @NotNull UUID skillId,
            @Size(max = 30) String requiredLevel
    ) {}

    public record FormField(
            @NotBlank @Size(max = 200) String label,
            @Size(max = 20) String fieldType,
            String options,
            Boolean required
    ) {}
}
