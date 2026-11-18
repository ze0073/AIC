package com.shijie.accessibility.security;

import com.shijie.accessibility.common.ApiException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserProfileServiceTest {
    AuthService users;
    JdbcTemplate jdbc;
    UserProfileService profiles;
    AuthService.UserView user;

    @BeforeEach void setup() {
        users = mock(AuthService.class); jdbc = mock(JdbcTemplate.class);
        user = new AuthService.UserView("user-a", "alice", "Alice", true, LocalDateTime.of(2026, 10, 3, 0, 0), "USER");
        when(users.current()).thenReturn(user);
        profiles = new UserProfileService(users, jdbc);
    }

    @Test void oldAccountsHaveEmptyOptionalFields() {
        var profile = profiles.current();
        assertThat(profile.id()).isEqualTo("user-a");
        assertThat(profile.email()).isEmpty(); assertThat(profile.phone()).isEmpty(); assertThat(profile.bio()).isEmpty();
        assertThat(profile.avatarUrl()).isNull();
    }

    @Test void updatesOnlyTheSessionOwnerAndKeepsAvatarOutOfTextUpdate() {
        profiles.update(new UserProfileService.ProfileUpdate("  新名称  ", "alice@example.com", "+86 138-0013-8000", "  hello  "));
        verify(jdbc).update(eq("UPDATE app_users SET display_name=?,updated_at=? WHERE id=?"), eq("新名称"), any(LocalDateTime.class), eq("user-a"));
        verify(jdbc).update(eq("INSERT INTO user_profiles(user_id,email,phone,bio) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE email=VALUES(email),phone=VALUES(phone),bio=VALUES(bio)"),
                eq("user-a"), eq("alice@example.com"), eq("+86 138-0013-8000"), eq("hello"));
        assertThat(profiles.current().platformRole()).isEqualTo("USER");
    }

    @Test void clearingOptionalFieldsIsSupported() {
        var body = new UserProfileService.ProfileUpdate("Alice", null, "  ", null);
        profiles.update(body);
        assertThat(body.email()).isEmpty(); assertThat(body.phone()).isEmpty(); assertThat(body.bio()).isEmpty();
    }

    @Test void invalidPhoneDoesNotWriteAnyData() {
        for (String phone : new String[]{"123", "1e13800138000", "+1234567890123456", "++12345678"}) {
            assertThatThrownBy(()->profiles.update(new UserProfileService.ProfileUpdate("Alice", "", phone, "")))
                    .isInstanceOf(ApiException.class).hasMessageContaining("手机号");
        }
        verifyNoInteractions(jdbc);
    }

    @Test void validatesTrimmedNameEmailAndFieldLengths() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new UserProfileService.ProfileUpdate("  ", "bad-address", "", "x".repeat(501)))).hasSize(3);
            assertThat(validator.validate(new UserProfileService.ProfileUpdate(" Alice ", " alice@example.com ", "", ""))).isEmpty();
            assertThat(validator.validate(new UserProfileService.ProfileUpdate("x".repeat(121), "", "x".repeat(33), ""))).hasSize(2);
        }
    }

    @Test void rejectsAnonymousAndDisabledSessionsBeforeReadingOrWriting() {
        when(users.current()).thenThrow(ApiException.unauthorized("请先登录"));
        assertThatThrownBy(profiles::current).isInstanceOf(ApiException.class);
        assertThatThrownBy(profiles::avatar).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->profiles.uploadAvatar(new byte[]{1})).isInstanceOf(ApiException.class);
        assertThatThrownBy(profiles::removeAvatar).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->profiles.update(new UserProfileService.ProfileUpdate("A", "", "", ""))).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void avatarRemovalTargetsCurrentUserOnly() {
        profiles.removeAvatar();
        verify(jdbc).update("UPDATE user_profiles SET avatar_png=NULL,avatar_version=NULL WHERE user_id=?", "user-a");
        when(users.current()).thenReturn(new AuthService.UserView("user-b", "bob", "Bob", true, user.createdAt(), "USER"));
        profiles.removeAvatar();
        verify(jdbc).update("UPDATE user_profiles SET avatar_png=NULL,avatar_version=NULL WHERE user_id=?", "user-b");
    }

    @Test void cropsCenterAndNormalizesBothSupportedFormats() throws Exception {
        var image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 400, 200); g.setColor(Color.GREEN); g.fillRect(100, 0, 200, 200); g.dispose();
        for (String format : new String[]{"png", "jpeg"}) {
            var bytes = new ByteArrayOutputStream(); ImageIO.write(image, format, bytes);
            byte[] normalized = UserProfileService.normalizeAvatar(bytes.toByteArray());
            var result = ImageIO.read(new ByteArrayInputStream(normalized));
            assertThat(result.getWidth()).isEqualTo(256); assertThat(result.getHeight()).isEqualTo(256);
            assertThat(new Color(result.getRGB(128, 128)).getGreen()).isGreaterThan(200);
        }
    }

    @Test void rejectsFakeUnsupportedOversizedAndHugeDimensionImages() throws Exception {
        assertThatThrownBy(()->UserProfileService.normalizeAvatar("<svg/>".getBytes())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->UserProfileService.normalizeAvatar(new byte[2*1024*1024+1])).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->UserProfileService.normalizeAvatar(new byte[0])).isInstanceOf(ApiException.class);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "gif", bytes);
        assertThatThrownBy(()->UserProfileService.normalizeAvatar(bytes.toByteArray())).isInstanceOf(ApiException.class);
        var huge = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB), "png", huge);
        assertThatThrownBy(()->UserProfileService.normalizeAvatar(huge.toByteArray())).isInstanceOf(ApiException.class).hasMessageContaining("4096");
    }

    @Test void uploadStoresOnlyNormalizedImageForCurrentUser() throws Exception {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", bytes);
        profiles.uploadAvatar(bytes.toByteArray());
        verify(jdbc).update(eq("INSERT INTO user_profiles(user_id,avatar_png,avatar_version) VALUES (?,?,?) ON DUPLICATE KEY UPDATE avatar_png=VALUES(avatar_png),avatar_version=VALUES(avatar_version)"),
                eq("user-a"), argThat((byte[] value)->value.length > 0), argThat((String value)->value.matches("[a-f0-9-]{36}")));
    }
}
