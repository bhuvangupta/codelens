package com.codelens.api;

import com.codelens.exception.NoOrganizationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NoOrganizationExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsToForbiddenWithTheExceptionMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleNoOrganization(
                new NoOrganizationException("Join an organization before submitting reviews"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(403, body.get("status"));
        assertEquals("Forbidden", body.get("error"));
        assertEquals("Join an organization before submitting reviews", body.get("message"));
        assertNotNull(body.get("timestamp"));
    }

    @Test
    void fallsBackToADefaultMessageWhenTheExceptionHasNone() {
        // Map.of rejects null values, so a null message must not reach the body
        ResponseEntity<Map<String, Object>> response =
                handler.handleNoOrganization(new NoOrganizationException(null));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Join an organization before submitting reviews", response.getBody().get("message"));
    }
}
