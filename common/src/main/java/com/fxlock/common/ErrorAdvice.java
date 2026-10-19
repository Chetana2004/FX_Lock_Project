package com.fxlock.common;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorAdvice 
{
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> api(ApiError e) 
  {
    return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler
  ({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  
  ResponseEntity<?> invalid(Exception e) 
  {
    return ResponseEntity.badRequest().body(Map.of("error", "Invalid request values or JSON"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(DataAccessException e) 
  {
    org.slf4j.LoggerFactory.getLogger(getClass()).error("Database operation failed", e);
    return ResponseEntity.status(503)
        .body(
            Map.of(
                "error", "Database unavailable; retry acceptance with the same idempotency key"));
  }
}




