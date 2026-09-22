package com.vsm.okno.web;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.service.PlanningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PlanningService.NotFoundException.class)
    public ResponseEntity<Dto.ApiError> notFound(PlanningService.NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Dto.ApiError("NOT_FOUND", ex.getMessage(), UUID.randomUUID().toString(), List.of()));
    }

    @ExceptionHandler(PlanningService.VersionConflictException.class)
    public ResponseEntity<Dto.ApiError> conflict(PlanningService.VersionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Dto.ApiError("VERSION_CONFLICT", "current version is " + ex.currentVersion,
                        UUID.randomUUID().toString(), List.of()));
    }
}
