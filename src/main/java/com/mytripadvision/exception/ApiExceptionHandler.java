package com.mytripadvision.exception;

import com.mytripadvision.agent.Outage;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is RFC 9457 Problem Details. Two extra fields keep the bundled web UI working: {@code success: false}
 * and {@code error} (the human-readable message the UI shows).
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    public record InvalidField(String field, String code, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<InvalidField> invalid = new ArrayList<>();
        for (var fieldError : ex.getBindingResult().getFieldErrors()) {
            invalid.add(new InvalidField(fieldError.getField(), errorCode(fieldError.getCode()),
                    fieldError.getDefaultMessage()));
        }
        ex.getBody().setProperty("errors", invalid);
        if (!invalid.isEmpty() && invalid.get(0).message() != null) {
            ex.getBody().setProperty("error", invalid.get(0).message());
        }
        return super.handleMethodArgumentNotValid(ex, headers, status, request);
    }

    /** The last step for every error Spring builds itself (bad JSON, missing parameter, wrong type...). */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            decorate(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidParameter(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "A required request parameter is missing or invalid.");
    }

    @ExceptionHandler(ThreadNotFoundException.class)
    ProblemDetail notFound(ThreadNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    @ExceptionHandler(NoPendingReviewException.class)
    ProblemDetail noPendingReview(NoPendingReviewException ex) {
        log.warn("Review refused: nothing is waiting for review");
        return problem(HttpStatus.CONFLICT, "No plan waiting for review", ex.getMessage());
    }

    @ExceptionHandler({VersionConflictException.class, OptimisticLockingFailureException.class})
    ProblemDetail versionConflict(RuntimeException ex) {
        log.warn("Version conflict on a chat");
        return problem(HttpStatus.CONFLICT, "Version conflict", "The chat was changed by another request. Reload it and try again.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (message.contains("uk_chat_threads_thread_id")) {
            log.warn("Duplicate thread id");
            return problem(HttpStatus.CONFLICT, "Version conflict", "This chat was created by another request. Reload it and try again.");
        }
        log.error("Unexpected data integrity error", ex);
        return unexpectedProblem();
    }

    /** A remote dependency failed (already logged with its service name, without keys or remote error text). */
    @ExceptionHandler(ExternalServiceException.class)
    ProblemDetail dependencyFailed(ExternalServiceException ex) {
        log.warn("Request failed because a dependency is unavailable service={}", ex.getService());
        // one line that names the service and says whether it is down or out of quota, never the remote text
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                Outage.reason(ex) + ". Please try again later.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return unexpectedProblem();
    }

    private static ProblemDetail unexpectedProblem() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return decorate(problem);
    }

    private static ProblemDetail decorate(ProblemDetail problem) {
        problem.setProperty("success", false);
        if (problem.getProperties() == null || !problem.getProperties().containsKey("error")) {
            problem.setProperty("error", problem.getDetail());
        }
        return problem;
    }

    private static String errorCode(String constraint) {
        return switch (constraint == null ? "" : constraint) {
            case "NotBlank", "NotNull", "NotEmpty" -> "required";
            case "Size" -> "size";
            case "Pattern" -> "pattern";
            default -> "invalid";
        };
    }
}
