package com.vsm.okno.web;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.service.PlanningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

// Every error body follows {code,message,traceId,details} per the ТЗ's error
// contract — extend here, not with ad-hoc error shapes in individual endpoints.
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Dto.ApiError> storage(org.springframework.dao.DataAccessException ex) {
        String trace=UUID.randomUUID().toString();
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class).error("Database operation failed; traceId={}",trace,ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Dto.ApiError("DATA_STORAGE_ERROR",
                "Database operation failed; no partial source version was accepted",trace,List.of()));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Dto.ApiError> sourceConstraint(org.springframework.dao.DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new Dto.ApiError("INVALID_REQUEST",
                "Source facts violate an identifier, interval, overlap or unique-version constraint",UUID.randomUUID().toString(),
                List.of(new Dto.ErrorDetail("change","Check IDs, trip overlaps, resource mappings and unique rule version"))));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Dto.ApiError> malformed(Exception ex) {
        return ResponseEntity.badRequest().body(new Dto.ApiError("MALFORMED_REQUEST","Request contains an invalid JSON value, ID or time",
                UUID.randomUUID().toString(),List.of()));
    }

    @ExceptionHandler(PlanningService.NotFoundException.class)
    public ResponseEntity<Dto.ApiError> notFound(PlanningService.NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Dto.ApiError("NOT_FOUND", ex.getMessage(), UUID.randomUUID().toString(), List.of()));
    }

    @ExceptionHandler(PlanningService.InvalidRequestException.class)
    public ResponseEntity<Dto.ApiError> invalid(PlanningService.InvalidRequestException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new Dto.ApiError("INVALID_REQUEST", ex.getMessage(), UUID.randomUUID().toString(),
                        List.of(new Dto.ErrorDetail(ex.field, ex.getMessage()))));
    }

    @ExceptionHandler(PlanningService.NotApprovableException.class)
    public ResponseEntity<Dto.ApiError> notApprovable(PlanningService.NotApprovableException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new Dto.ApiError("PLAN_NOT_APPROVABLE", ex.getMessage(), UUID.randomUUID().toString(), List.of()));
    }

    @ExceptionHandler(PlanningService.VersionConflictException.class)
    public ResponseEntity<Dto.ApiError> conflict(PlanningService.VersionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Dto.ApiError("VERSION_CONFLICT", "current version is " + ex.currentVersion,
                        UUID.randomUUID().toString(), List.of()));
    }

    @ExceptionHandler(PlanningService.IdempotencyConflictException.class)
    public ResponseEntity<Dto.ApiError> conflict(PlanningService.IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Dto.ApiError("IDEMPOTENCY_KEY_REUSED", ex.getMessage(), UUID.randomUUID().toString(), List.of()));
    }
}
