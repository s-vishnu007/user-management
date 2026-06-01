package com.example.licenseverifier.spring;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class LicensePermissionDeniedAdvice {

    @ExceptionHandler(LicensePermissionDeniedException.class)
    public ProblemDetail handle(LicensePermissionDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        pd.setType(URI.create("license/permission-denied"));
        pd.setTitle("Permission denied");
        pd.setProperty("missingPermission", ex.getMissingPermission());
        return pd;
    }
}
