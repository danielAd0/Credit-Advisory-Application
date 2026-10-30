package za.co.mafori.creditadvisory.user;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.common.web.PageResponse;

/** User management - ADMIN only (SRS section 4). */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(search, page, size);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
    }

    @PatchMapping("/{id}/roles")
    public UserResponse changeRoles(@PathVariable long id, @Valid @RequestBody ChangeRolesRequest request) {
        return service.changeRoles(id, request);
    }

    @PatchMapping("/{id}/consultant")
    public UserResponse changeConsultant(@PathVariable long id, @Valid @RequestBody LinkConsultantRequest request) {
        return service.changeConsultant(id, request);
    }

    @PatchMapping("/{id}/enabled")
    public UserResponse changeEnabled(@PathVariable long id, @Valid @RequestBody ChangeEnabledRequest request) {
        return service.changeEnabled(id, request);
    }

    @PostMapping("/{id}/password-reset")
    public UserResponse resetPassword(@PathVariable long id, @Valid @RequestBody ResetPasswordRequest request) {
        return service.resetPassword(id, request);
    }

    @PostMapping("/{id}/unlock")
    public UserResponse unlock(@PathVariable long id) {
        return service.unlock(id);
    }
}
