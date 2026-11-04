package com.ftp.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the JSON error contract produced by {@link GlobalExceptionHandler}.
 * A mocked request supplies the URI used in the response body.
 */
@DisplayName("GlobalExceptionHandler unit tests")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/trading/orders");
    }

    private void assertBody(ResponseEntity<Map<String, Object>> response, int status, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("status", status);
        assertThat(response.getBody()).containsEntry("message", message);
        assertThat(response.getBody()).containsEntry("path", "/api/trading/orders");
        assertThat(response.getBody()).containsKey("timestamp");
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void apiExceptionUsesItsOwnStatus() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleApiException(new ConflictException("Username is already taken"), request);

        assertBody(response, HttpStatus.CONFLICT.value(), "Username is already taken");
        assertThat(response.getBody()).containsEntry("error", "Conflict");
    }

    @Test
    void validationErrorsExposeFieldMessages() throws Exception {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", SamplePayload.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new SamplePayload(), "samplePayload");
        bindingResult.addError(new FieldError("samplePayload", "quantity", "must be greater than 0"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        @SuppressWarnings("unchecked")
        Map<String, String> fieldErrors = (Map<String, String>) response.getBody().get("fieldErrors");
        assertThat(fieldErrors).containsEntry("quantity", "must be greater than 0");
        assertThat(response.getBody()).containsEntry("message", "quantity: must be greater than 0");
    }

    @Test
    void missingParameterIsReportedByName() {
        ResponseEntity<Map<String, Object>> response = handler.handleMissingParam(
                new MissingServletRequestParameterException("query", "String"), request);

        assertBody(response, HttpStatus.BAD_REQUEST.value(), "Required parameter 'query' is missing");
    }

    @Test
    void typeMismatchIsReportedByName() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "orderId", null, null);

        ResponseEntity<Map<String, Object>> response = handler.handleTypeMismatch(ex, request);

        assertBody(response, HttpStatus.BAD_REQUEST.value(), "Parameter 'orderId' has an invalid value");
    }

    @Test
    void badCredentialsDoNotLeakWhetherUserExists() {
        ResponseEntity<Map<String, Object>> response = handler.handleBadCredentials(
                new BadCredentialsException("Bad credentials"), request);

        assertBody(response, HttpStatus.UNAUTHORIZED.value(), "Invalid username or password");
    }

    @Test
    void usernameNotFoundIsReportedAsUnauthorized() {
        ResponseEntity<Map<String, Object>> response = handler.handleUsernameNotFound(
                new UsernameNotFoundException("User not found: x"), request);

        assertBody(response, HttpStatus.UNAUTHORIZED.value(), "Invalid username or password");
    }

    @Test
    void disabledAccountIsForbidden() {
        ResponseEntity<Map<String, Object>> response = handler.handleDisabled(
                new DisabledException("disabled"), request);

        assertBody(response, HttpStatus.FORBIDDEN.value(), "Account is disabled");
    }

    @Test
    void accessDeniedIsForbidden() {
        ResponseEntity<Map<String, Object>> response = handler.handleAccessDenied(
                new AccessDeniedException("nope"), request);

        assertBody(response, HttpStatus.FORBIDDEN.value(), "Access is denied");
    }

    @Test
    void noResourceFoundIsNotFound() {
        // The handler ignores the exception's internals and returns a fixed body,
        // so a mock avoids depending on a constructor signature that varies
        // between Spring Framework versions.
        ResponseEntity<Map<String, Object>> response = handler.handleNoResource(
                mock(NoResourceFoundException.class), request);

        assertBody(response, HttpStatus.NOT_FOUND.value(), "Resource not found");
    }

    @Test
    void dataIntegrityViolationBecomesConflict() {
        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("duplicate key"), request);

        assertBody(response, HttpStatus.CONFLICT.value(), "The request conflicts with existing data");
    }

    @Test
    void genericExceptionHidesInternals() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new IllegalStateException("secret internal detail"), request);

        assertBody(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), "An unexpected error occurred");
        assertThat(response.getBody().get("message")).isNotEqualTo("secret internal detail");
    }

    // -- helpers ---------------------------------------------------------

    @SuppressWarnings("unused")
    private void dummy(SamplePayload payload) {
    }

    static class SamplePayload {
    }
}
