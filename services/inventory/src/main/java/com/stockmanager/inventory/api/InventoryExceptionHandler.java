package com.stockmanager.inventory.api;

import com.stockmanager.inventory.domain.InsufficientStockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class InventoryExceptionHandler {

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail handleInsufficientStock(InsufficientStockException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
