package com.shijie.accessibility.api;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.security.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth")
public class UserProfileController {
    private final UserProfileService profiles;

    public UserProfileController(UserProfileService profiles) { this.profiles = profiles; }

    @GetMapping("/profile")
    ResponseEntity<UserProfileService.ProfileView> profile() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.current());
    }

    @PutMapping("/profile")
    UserProfileService.ProfileView update(@Valid @RequestBody UserProfileService.ProfileUpdate body) { return profiles.update(body); }

    @GetMapping(value="/avatar", produces=MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> avatar() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff").body(profiles.avatar());
    }

    @PostMapping(value="/avatar", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    UserProfileService.ProfileView upload(@RequestParam(value="file", required=false) MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > 2 * 1024 * 1024)
            throw ApiException.badRequest("INVALID_AVATAR_SIZE", "头像文件大小需在 2 MB 以内");
        return profiles.uploadAvatar(file.getBytes());
    }

    @DeleteMapping("/avatar")
    UserProfileService.ProfileView remove() { return profiles.removeAvatar(); }
}
