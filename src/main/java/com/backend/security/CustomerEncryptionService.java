package com.backend.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.backend.config.MarketplaceProperties;

@Service
public class CustomerEncryptionService {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public CustomerEncryptionService(MarketplaceProperties properties) {
        this.key = new SecretKeySpec(
                Base64.getDecoder().decode(properties.credentialEncryptionKey()),
                "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext);
            return VERSION + ":" + Base64.getEncoder().encodeToString(payload.array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot encrypt customer PII", exception);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        String[] parts = encrypted.split(":", 2);
        if (parts.length != 2 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("Unsupported customer PII format");
        }
        try {
            ByteBuffer payload = ByteBuffer.wrap(Base64.getDecoder().decode(parts[1]));
            byte[] iv = new byte[IV_BYTES];
            payload.get(iv);
            byte[] ciphertext = new byte[payload.remaining()];
            payload.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot decrypt customer PII", exception);
        }
    }

    public String generateLookupHmac(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String normalized = normalize(rawValue);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] bytes = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(bytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 for PII lookup", exception);
        }
    }

    public String keyVersion() {
        return VERSION;
    }

    private String normalize(String val) {
        String trimmed = val.trim().toLowerCase();
        // If it looks like a phone number, remove all non-digits
        if (trimmed.matches("^[+\\d\\s()-]{7,25}$")) {
            return trimmed.replaceAll("[^\\d]", "");
        }
        return trimmed;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
