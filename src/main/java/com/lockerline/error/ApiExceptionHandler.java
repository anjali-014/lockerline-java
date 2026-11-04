package com.lockerline.error;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.lockerline.config.RequestIdFilter;
/**
 * The one place that turns errors into a ProblemDetail (application/problem+json). The base class already answers
 * Spring's own errors - an unknown path, a wrong method - as ProblemDetails; this adds the domain refusals and the
  *  requests that are not valid: a bad query value, a body that fails Bean Validation, a body Jackson cannot read -
  * and every unexpected error.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
       
      @ExceptionHandler(DomainException.class)
    public ProblemDetail refusal(DomainException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status()), e.getMessage());
        problem.setType(URI.create(e.type()));
                problem.setTitle(e.title());
        e.properties().forEach(problem::setProperty);
        return problem;
    }

    /**
     * Anything else that goes wrong is a 500 with a generic detail - never the exception's message or a stack
          * trace - and the request's id, which the log line carries too.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
                String requestId = MDC.get(RequestIdFilter.KEY);
        log.error("unexpected error requestId={}", requestId, e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side; quote the request id if you report it");
                        problem.setType(URI.create("/errors/internal"));
        problem.setTitle("Internal error");
        problem.setProperty("requestId", requestId);
        return problem;
    }
        /** A query value that does not fit its type - ?size=XL, ?state=open - is 400 /errors/validation.  */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
             HttpStatusCode status, WebRequest request) {
                      String field = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
                return handleExceptionInternal(ex, validation(List.of(error(field, "'" + ex.getValue()
                + "' is not an allowed value"))), headers, HttpStatus.BAD_REQUEST, request);
    }

/** A body that fails Bean Validation (@NotNull, @NotBlank): 400 /errors/validation naming every field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
                                                                            List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> error(f.getField(), String.valueOf(f.getDefaultMessage()))).toList();
        return handleExceptionInternal(ex, validation(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * A body Jackson cannot read - {"sizeClass": "XL"} fails before Bean Validation ever runs - is still 400
     * /errors/validation, naming the field Jackson stopped at.
     */
         @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
                                                                            String field = "body";
        if (ex.getCause() instanceof JsonMappingException json && !json.getPath().isEmpty()) {
            field = json.getPath().get(json.getPath().size() - 1).getFieldName();
        }
                return handleExceptionInternal(ex, validation(List.of(error(field, "this value cannot be read"))), headers,
                HttpStatus.BAD_REQUEST, request);
    }

    private static Map<String, String> error(String field, String message) {
        return Map.of("field", String.valueOf(field), "message", message);
    }

    private static ProblemDetail validation(List<Map<String, String>> errors){
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is not valid");
        problem.setType(URI.create("/errors/validation"));
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return problem;
    }
}
            
                                                  