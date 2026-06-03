package com.example.cp.users;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user.read') or #id == T(com.example.cp.common.SecurityUtils).currentUserId()")
    public UserDto get(@PathVariable UUID id) {
        return UserDto.from(userService.get(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('user.write') or #id == T(com.example.cp.common.SecurityUtils).currentUserId()")
    public UserDto patch(@PathVariable UUID id, @Valid @RequestBody UpdateProfileRequest body) {
        return UserDto.from(userService.updateProfile(id, body.fullName()));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('user.write')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        userService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('user.write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/change-password")
    public ResponseEntity<Void> changePassword(@PathVariable UUID id, @Valid @RequestBody ChangePasswordRequest body) {
        AuthenticatedUser me = SecurityUtils.requireUser();
        if (!me.userId().equals(id)) {
            throw ApiException.forbidden("Can only change own password");
        }
        userService.changePassword(id, body.oldPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record UpdateProfileRequest(@Size(max = 255) String fullName) {}

    public record ChangePasswordRequest(
            @NotBlank String oldPassword,
            @NotBlank @Size(min = 8, max = 255) String newPassword
    ) {}
}
