package com.household.finance.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Turns failures into one plain message the page can show. Messages are fixed text or come from the services, which
 * build them from numbers and dates only. Request bodies and uploaded files are never echoed back or logged.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record Message(String message) {}

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Message> notAllowed(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Message(e.getMessage()));
    }

    @ExceptionHandler(NoLocalUserException.class)
    public ResponseEntity<Message> noUser(NoLocalUserException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Message(e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Message> unreadable() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Message("The request could not be read. Check the amounts and the date. Nothing was saved."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Message> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new Message("The file is larger than 10 MB. Nothing was saved."));
    }
}
