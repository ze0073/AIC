package com.shijie.accessibility.security;

import com.shijie.accessibility.common.ApiException;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class UserProfileService {
    private final AuthService users;
    private final JdbcTemplate jdbc;

    public UserProfileService(AuthService users, JdbcTemplate jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    public ProfileView current() {
        var user = users.current();
        var profiles = jdbc.query("SELECT email,phone,bio,avatar_version FROM user_profiles WHERE user_id=?",
                (rs, n) -> new Details(rs.getString("email"), rs.getString("phone"), rs.getString("bio"), rs.getString("avatar_version")), user.id());
        var details = profiles.isEmpty() ? new Details("", "", "", null) : profiles.getFirst();
        return new ProfileView(user.id(), user.username(), user.displayName(), user.enabled(), user.createdAt(), user.platformRole(),
                details.email(), details.phone(), details.bio(), details.avatarVersion() == null ? null : "/api/auth/avatar?v=" + details.avatarVersion());
    }

    @Transactional
    public ProfileView update(ProfileUpdate body) {
        var user = users.current();
        if (!body.phone().isEmpty()) {
            String digits = body.phone().replaceAll("[^0-9]", "");
            if (!body.phone().matches("\\+?[0-9 ()-]+") || digits.length() < 7 || digits.length() > 15)
                throw ApiException.badRequest("INVALID_PHONE", "请输入有效手机号，可包含国际区号、空格和连字符");
        }
        jdbc.update("UPDATE app_users SET display_name=?,updated_at=? WHERE id=?", body.displayName(), LocalDateTime.now(), user.id());
        jdbc.update("INSERT INTO user_profiles(user_id,email,phone,bio) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE email=VALUES(email),phone=VALUES(phone),bio=VALUES(bio)",
                user.id(), body.email(), body.phone(), body.bio());
        return current();
    }

    public byte[] avatar() {
        var user = users.current();
        var rows = jdbc.query("SELECT avatar_png FROM user_profiles WHERE user_id=?", (rs, n) -> rs.getBytes("avatar_png"), user.id());
        if (rows.isEmpty() || rows.getFirst() == null) throw ApiException.notFound("尚未设置头像");
        return rows.getFirst();
    }

    @Transactional
    public ProfileView uploadAvatar(byte[] input) {
        var user = users.current();
        byte[] png = normalizeAvatar(input);
        jdbc.update("INSERT INTO user_profiles(user_id,avatar_png,avatar_version) VALUES (?,?,?) ON DUPLICATE KEY UPDATE avatar_png=VALUES(avatar_png),avatar_version=VALUES(avatar_version)",
                user.id(), png, UUID.randomUUID().toString());
        return current();
    }

    @Transactional
    public ProfileView removeAvatar() {
        var user = users.current();
        jdbc.update("UPDATE user_profiles SET avatar_png=NULL,avatar_version=NULL WHERE user_id=?", user.id());
        return current();
    }

    static byte[] normalizeAvatar(byte[] input) {
        if (input == null || input.length == 0 || input.length > 2 * 1024 * 1024)
            throw ApiException.badRequest("INVALID_AVATAR_SIZE", "头像文件大小需在 2 MB 以内");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw ApiException.badRequest("INVALID_AVATAR", "请上传有效的 PNG 或 JPEG 图片");
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"png".equalsIgnoreCase(format) && !"jpeg".equalsIgnoreCase(format))
                    throw ApiException.badRequest("INVALID_AVATAR", "头像仅支持 PNG 或 JPEG 图片");
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096)
                    throw ApiException.badRequest("INVALID_AVATAR_DIMENSIONS", "头像尺寸不能超过 4096 × 4096");
                var source = reader.read(0);
                var target = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                var graphics = target.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int size = Math.min(width, height), x = (width - size) / 2, y = (height - size) / 2;
                    graphics.drawImage(source, 0, 0, 256, 256, x, y, x + size, y + size, null);
                } finally { graphics.dispose(); }
                var output = new ByteArrayOutputStream();
                ImageIO.write(target, "png", output);
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException error) {
            throw ApiException.badRequest("INVALID_AVATAR", "图片无法读取，请重新选择 PNG 或 JPEG 图片");
        }
    }

    public record ProfileUpdate(@NotBlank @Size(max=120) String displayName,
                                @Email @Size(max=254) String email,
                                @Size(max=32) String phone,
                                @Size(max=500) String bio) {
        public ProfileUpdate {
            displayName = clean(displayName); email = clean(email); phone = clean(phone); bio = clean(bio);
        }
        private static String clean(String value) { return value == null ? "" : value.strip(); }
    }
    private record Details(String email, String phone, String bio, String avatarVersion) {}
    public record ProfileView(String id, String username, String displayName, boolean enabled, LocalDateTime createdAt,
                              String platformRole, String email, String phone, String bio, String avatarUrl) {}
}
