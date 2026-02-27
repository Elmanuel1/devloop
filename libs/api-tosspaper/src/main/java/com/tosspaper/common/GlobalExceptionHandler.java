package com.tosspaper.common;

import com.svix.exceptions.WebhookVerificationException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.tosspaper.common.exception.ServiceException;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;

@ControllerAdvice
@Slf4j
@Order(1)
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handle(MethodArgumentNotValidException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        var err = ErrorTranslator.from(ex.getBindingResult());
        headers.put(HttpHeaders.CONTENT_TYPE, Collections.singletonList(MimeTypeUtils.APPLICATION_JSON_VALUE));
        return new ResponseEntity<>(err, headers, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handle(HttpMessageNotReadableException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        return new ResponseEntity<>(ErrorTranslator.from(ex), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Object> handle(
            MissingServletRequestPartException ex, WebRequest request) {
        return new ResponseEntity<>(
                new ApiError(ApiErrorMessages.INVALID_HEADER_FORMAT, ApiErrorMessages.REQUEST_PART_MUST_BE_PROVIDED.formatted(ex.getRequestPartName())),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Object> handleMissingRequestHeaderException(
            MissingRequestHeaderException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        return new ResponseEntity<>(
                new ApiError(ApiErrorMessages.INVALID_HEADER_FORMAT, ApiErrorMessages.REQUIRED_HEADER_IS_MISSING.formatted(ex.getHeaderName())),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Object> handleNotFoundException(NotFoundException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(DuplicateException.class)
    public ResponseEntity<Object> handleDuplicateCompanyException(DuplicateException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(com.tosspaper.models.exception.ForbiddenException.class)
    public ResponseEntity<Object> handleForbiddenException(com.tosspaper.models.exception.ForbiddenException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Object> handleUnauthorizedException(UnauthorizedException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Object> handleBadRequestException(BadRequestException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(com.tosspaper.file.exception.FileUploadException.class)
    public ResponseEntity<Object> handleFileUploadException(com.tosspaper.file.exception.FileUploadException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(com.tosspaper.file.exception.FileDeleteException.class)
    public ResponseEntity<Object> handleFileDeleteException(com.tosspaper.file.exception.FileDeleteException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Object> handleServiceException(ServiceException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(com.tosspaper.file.exception.FileServiceException.class)
    public ResponseEntity<Object> handleFileServiceException(com.tosspaper.file.exception.FileServiceException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(EmailVerificationRequiredException.class)
    public ResponseEntity<Object> handleEmailVerificationRequiredException(EmailVerificationRequiredException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(WebhookVerificationException.class)
    public ResponseEntity<Object> handleWebhookVerificationException(WebhookVerificationException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError("invalid_secret", ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(com.tosspaper.models.exception.StaleVersionException.class)
    public ResponseEntity<Object> handleStaleVersionException(com.tosspaper.models.exception.StaleVersionException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.PRECONDITION_FAILED);
    }

    @ExceptionHandler(com.tosspaper.models.exception.InvalidStatusTransitionException.class)
    public ResponseEntity<Object> handleInvalidStatusTransitionException(com.tosspaper.models.exception.InvalidStatusTransitionException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(com.tosspaper.models.exception.CannotDeleteException.class)
    public ResponseEntity<Object> handleCannotDeleteException(com.tosspaper.models.exception.CannotDeleteException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(com.tosspaper.models.exception.IfMatchRequiredException.class)
    public ResponseEntity<Object> handleIfMatchRequiredException(com.tosspaper.models.exception.IfMatchRequiredException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.valueOf(428));
    }

    @ExceptionHandler(com.tosspaper.models.exception.InvalidETagException.class)
    public ResponseEntity<Object> handleInvalidETagException(com.tosspaper.models.exception.InvalidETagException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(com.tosspaper.models.exception.InvalidCursorException.class)
    public ResponseEntity<Object> handleInvalidCursorException(com.tosspaper.models.exception.InvalidCursorException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(com.tosspaper.models.exception.DocumentNotReadyException.class)
    public ResponseEntity<Object> handleDocumentNotReadyException(com.tosspaper.models.exception.DocumentNotReadyException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(com.tosspaper.models.exception.EntityStaleException.class)
    public ResponseEntity<Object> handleEntityStaleException(com.tosspaper.models.exception.EntityStaleException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(com.tosspaper.models.exception.UnresolvedConflictsException.class)
    public ResponseEntity<Object> handleUnresolvedConflictsException(com.tosspaper.models.exception.UnresolvedConflictsException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(com.tosspaper.models.exception.ExtractionNotApplicableException.class)
    public ResponseEntity<Object> handleExtractionNotApplicableException(com.tosspaper.models.exception.ExtractionNotApplicableException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(com.tosspaper.models.exception.NotImplementedException.class)
    public ResponseEntity<Object> handleNotImplementedException(com.tosspaper.models.exception.NotImplementedException ex, WebRequest request) {
        log.error(ApiErrorMessages.ERROR_PROCESSING_REQUEST, ex);
        var apiError = new ApiError(ex.getCode(), ex.getMessage());
        return new ResponseEntity<>(apiError, HttpStatus.NOT_IMPLEMENTED);
    }
}