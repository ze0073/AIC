package com.shijie.accessibility.online;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class CredentialCipher {
    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String configuredKey;
    private SecretKeySpec key;

    public CredentialCipher(@Value("${app.security.credential-key:local-development-key-change-before-deployment}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    @PostConstruct
    void initialize() throws Exception {
        this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                .digest(configuredKey.getBytes(StandardCharsets.UTF_8)), "AES");
        if (configuredKey.startsWith("local-development")) {
            log.warn("Using the development credential encryption key; set APP_CREDENTIAL_KEY before shared deployment");
        }
    }

    public String encrypt(String plaintext, String binding) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("凭据加密失败", ex);
        }
    }

    public String decrypt(String ciphertext, String binding) {
        try {
            byte[] value = Base64.getDecoder().decode(ciphertext);
            byte[] nonce = Arrays.copyOfRange(value, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Arrays.copyOfRange(value, 12, value.length)), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("凭据解密失败", ex);
        }
    }

    public String fingerprint(String plaintext) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("凭据指纹生成失败",ex); }
    }
}
