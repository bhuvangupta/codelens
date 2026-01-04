package com.codelens.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-GCM encryption service for sensitive data like git tokens.
 * Uses PBKDF2 for secure key derivation with random salt per encryption.
 *
 * Encrypted format: ENC:base64(salt + iv + ciphertext)
 * - Salt: 16 bytes (random per encryption)
 * - IV: 12 bytes (random per encryption)
 * - Ciphertext: variable length with GCM auth tag
 */
@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int SALT_LENGTH = 16;
    private static final int PBKDF2_ITERATIONS = 100000;
    private static final int KEY_LENGTH_BITS = 256;

    private final String encryptionKey;
    private final boolean enabled;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptionService(
            @Value("${codelens.security.encryption-key:}") String encryptionKey,
            @Value("${codelens.security.encryption-enabled:true}") boolean enabled) {
        this.encryptionKey = encryptionKey;
        this.enabled = enabled && encryptionKey != null && !encryptionKey.isEmpty();

        if (this.enabled) {
            log.info("Encryption service initialized with AES-256-GCM (PBKDF2 with random salt)");
        } else {
            log.warn("Encryption service disabled - sensitive data will be stored in plain text");
        }
    }

    /**
     * Derive a secret key from the encryption key and salt using PBKDF2.
     */
    private SecretKey deriveKey(byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(encryptionKey.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    /**
     * Encrypt a plaintext string.
     * Returns the original string if encryption is disabled.
     *
     * Output format: ENC:base64(salt[16] + iv[12] + ciphertext)
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }

        if (!enabled) {
            return plainText;
        }

        try {
            // Generate random salt for this encryption
            byte[] salt = new byte[SALT_LENGTH];
            secureRandom.nextBytes(salt);

            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // Derive key from encryption key + random salt
            SecretKey secretKey = deriveKey(salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Combine: salt (16 bytes) + IV (12 bytes) + ciphertext
            byte[] combined = new byte[salt.length + iv.length + cipherText.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(iv, 0, combined, salt.length, iv.length);
            System.arraycopy(cipherText, 0, combined, salt.length + iv.length, cipherText.length);

            // Prefix with "ENC:" to identify encrypted values
            return "ENC:" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt an encrypted string.
     * Returns the original string if it's not encrypted or encryption is disabled.
     *
     * Expected format: ENC:base64(salt[16] + iv[12] + ciphertext)
     */
    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }

        // Check if the value is encrypted
        if (!encryptedText.startsWith("ENC:")) {
            return encryptedText; // Return as-is if not encrypted
        }

        if (!enabled) {
            log.warn("Cannot decrypt value - encryption service is disabled");
            return encryptedText;
        }

        try {
            String base64Data = encryptedText.substring(4); // Remove "ENC:" prefix
            byte[] combined = Base64.getDecoder().decode(base64Data);

            // Extract salt, IV, and ciphertext
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[combined.length - SALT_LENGTH - GCM_IV_LENGTH];

            System.arraycopy(combined, 0, salt, 0, salt.length);
            System.arraycopy(combined, salt.length, iv, 0, iv.length);
            System.arraycopy(combined, salt.length + iv.length, cipherText, 0, cipherText.length);

            // Derive key from encryption key + salt
            SecretKey secretKey = deriveKey(salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
