package com.shijie.accessibility.online;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {
    @Test
    void bindsCiphertextToProjectTargetCredentialAndOrigin() throws Exception {
        CredentialCipher cipher = new CredentialCipher("a-test-key-that-is-not-used-outside-this-test");
        cipher.initialize();
        String encrypted = cipher.encrypt("Bearer secret-token", "project:target:credential:https://example.test");

        assertThat(encrypted).doesNotContain("secret-token");
        assertThat(cipher.decrypt(encrypted, "project:target:credential:https://example.test")).isEqualTo("Bearer secret-token");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "another-binding")).isInstanceOf(IllegalStateException.class);
    }
}
