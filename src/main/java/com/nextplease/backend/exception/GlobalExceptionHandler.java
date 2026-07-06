package com.nextplease.backend.exception;

import com.nextplease.backend.dto.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    ResponseEntity<ApiResponse<Void>> handleAppException(AppException exception) {
        ApiResponse<Void> body = exception.getErrorCode() != null
                ? ApiResponse.errorWithCode(exception.getMessage(), exception.getErrorCode())
                : ApiResponse.error(exception.getMessage());
        return ResponseEntity.status(exception.getStatus()).body(body);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        // Prefer a DTO-declared message (e.g. @NotBlank(message = "...")) shown on
        // its own — prefixing it with the raw field name ("displayName: Tên...")
        // reads as a leaked technical detail once the message itself is already
        // a full user-facing sentence. Fields with no custom message (still using
        // Jakarta's default English text) keep the field-name prefix so the
        // response stays traceable to a field.
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> {
                    String defaultMessage = error.getDefaultMessage();
                    boolean looksLikeCustomMessage = defaultMessage != null
                            && !defaultMessage.isBlank()
                            && Character.isUpperCase(defaultMessage.charAt(0));
                    return looksLikeCustomMessage
                            ? defaultMessage
                            : error.getField() + ": " + defaultMessage;
                })
                .orElse("Validation failed");

        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    ResponseEntity<ApiResponse<Void>> handleUnauthorized(Exception exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiResponse<Void>> handleDatabase(DataAccessException exception) {
        log.error("Database operation failed", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Database is temporarily unavailable. Please retry in a moment."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unexpected application error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Unexpected server error"));
    }
}
