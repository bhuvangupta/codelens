package com.codelens.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

    @Test
    void failsWhenEncryptionEnabledAndKeyMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EncryptionService("", true));
        assertTrue(ex.getMessage().contains("ENCRYPTION_KEY"));
    }

    @Test
    void allowsPlaintextWhenExplicitlyDisabled() {
        EncryptionService service = new EncryptionService("", false);
        assertFalse(service.isEnabled());

        String value = "sensitive";
        assertEquals(value, service.encrypt(value));
        assertEquals(value, service.decrypt(value));
    }

    @Test
    void roundTripsWhenKeyIsPresent() {
        String key = "a-very-secret-key-that-is-long-enough-12345";
        EncryptionService service = new EncryptionService(key, true);
        assertTrue(service.isEnabled());

        String value = "github_pat_xxx";
        String encrypted = service.encrypt(value);
        assertTrue(encrypted.startsWith("ENC:"));
        assertEquals(value, service.decrypt(encrypted));
    }
}
