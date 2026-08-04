package com.backend.security;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

@Component
public class TemporaryPasswordGenerator {

    private static final char[] UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWERCASE = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] SPECIAL = "!@#$%*-_=+".toCharArray();
    private static final char[] ALL =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%*-_=+".toCharArray();
    private static final int PASSWORD_LENGTH = 20;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        char[] password = new char[PASSWORD_LENGTH];
        password[0] = pick(UPPERCASE);
        password[1] = pick(LOWERCASE);
        password[2] = pick(DIGITS);
        password[3] = pick(SPECIAL);
        for (int index = 4; index < password.length; index++) {
            password[index] = pick(ALL);
        }
        for (int index = password.length - 1; index > 0; index--) {
            int swapIndex = secureRandom.nextInt(index + 1);
            char value = password[index];
            password[index] = password[swapIndex];
            password[swapIndex] = value;
        }
        return new String(password);
    }

    private char pick(char[] values) {
        return values[secureRandom.nextInt(values.length)];
    }
}
