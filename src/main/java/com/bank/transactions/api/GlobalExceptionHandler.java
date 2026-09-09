package com.bank.transactions.api;

import com.bank.transactions.domain.BankingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;


@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BankingException.class)
    public ProblemDetail onBankingException(BankingException e) {
        HttpStatus status = switch (e.code()) {
            case INVALID_AMOUNT, INVALID_PAGE_REQUEST -> HttpStatus.BAD_REQUEST;
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            // 409: conflicts with current state, so a retry after a deposit is meaningful.
            case INSUFFICIENT_FUNDS -> HttpStatus.CONFLICT;
            // 422: no balance ever makes a self-transfer valid, so retrying is pointless.
            case SELF_TRANSFER -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return problem(status, e.code().name(), e.getMessage());
    }

    // A malformed uuid in the path is a bad request, not a missing account: 404 would send
    // the client looking in the wrong place.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "'" + e.getValue() + "' is not a valid " + e.getName());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onInvalidBody(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "request body is not readable");
    }

    private ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("errorCode", code);
        return problem;
    }
}