package com.szpu.algohub.auth;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import com.szpu.algohub.common.ApiResult;
@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetController {
 private final PasswordResetService service;
 public PasswordResetController(PasswordResetService service){this.service=service;}
 public record Reset(@NotBlank @Email @Size(max=254) String email,@Pattern(regexp="[0-9]{6}") @NotBlank String code,@NotBlank @Size(min=8,max=64) String password){}
 @PostMapping("/code") public ApiResult<String> send(@Valid @RequestBody EmailIdentityController.SendRequest r,HttpServletRequest http){service.send(r.email(),http.getRemoteAddr());return ApiResult.ok("如果该邮箱已验证绑定到可用账号，将收到重置验证码");}
 @PostMapping public ApiResult<Void> reset(@Valid @RequestBody Reset r,HttpServletRequest http){service.reset(r.email(),r.code(),r.password(),http.getRemoteAddr(),http.getHeader("User-Agent"));return ApiResult.ok();}
}
