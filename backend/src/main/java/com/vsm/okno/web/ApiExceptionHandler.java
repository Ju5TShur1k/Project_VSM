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
}
