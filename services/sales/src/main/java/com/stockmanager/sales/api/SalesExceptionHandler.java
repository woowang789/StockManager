package com.stockmanager.sales.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
class SalesExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> handleIllegalArgument(IllegalArgumentException exception){
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
}
