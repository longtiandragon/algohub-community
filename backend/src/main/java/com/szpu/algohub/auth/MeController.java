package com.szpu.algohub.auth;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.AuthDtos.ChangePasswordRequest;
import com.szpu.algohub.auth.AuthDtos.DeleteAccountRequest;
import com.szpu.algohub.auth.AuthDtos.MeView;
import com.szpu.algohub.auth.AuthDtos.PrivacyRequest;
import com.szpu.algohub.auth.AuthDtos.PrivacyView;
import com.szpu.algohub.auth.AuthDtos.PublicProfileView;
import com.szpu.algohub.auth.AuthDtos.UpdateProfileRequest;
import com.szpu.algohub.common.ApiResult;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** 个人信息、隐私设置、账号安全与数据导出/注销。 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final OnboardingService onboarding;
    private final AuthService authService;
    private final RefreshCookies cookies;
    private final SecurityActionService actions;

    public MeController(AuthService authService, RefreshCookies cookies, SecurityActionService actions, OnboardingService onboarding) {
        this.onboarding = onboarding;
        this.actions = actions;
        this.cookies = cookies;
        this.authService = authService;
    }

    @PostMapping("/onboarding")
    public ApiResult<MeView> completeOnboarding(@Valid @RequestBody AuthDtos.OnboardingRequest request) {
        onboarding.complete(CurrentUser.requireId(), request);
        return ApiResult.ok(authService.me(CurrentUser.requireId()));
    }

    @PostMapping("/complete-academic-profile")
    public ApiResult<MeView> completeAcademic(@Valid @RequestBody AuthDtos.AcademicRequest request) {
        onboarding.completeAcademic(CurrentUser.requireId(), request);
        return ApiResult.ok(authService.me(CurrentUser.requireId()));
    }

    @PostMapping("/academic-profile")
    public ApiResult<MeView> updateAcademic(@Valid @RequestBody AuthDtos.AcademicRequest request) {
        onboarding.updateAcademic(CurrentUser.requireId(), request);
        return ApiResult.ok(authService.me(CurrentUser.requireId()));
    }

    @GetMapping
    public ApiResult<MeView> me() {
        return ApiResult.ok(authService.me(CurrentUser.requireId()));
    }

    @PostMapping("/profile")
    public ApiResult<MeView> updateProfile(@Valid @RequestBody UpdateProfileRequest request,
                                           HttpServletRequest http) {
        return ApiResult.ok(authService.updateProfile(CurrentUser.requireId(), request,
                http.getRemoteAddr(), http.getHeader("User-Agent")));
    }

    @GetMapping("/privacy")
    public ApiResult<PrivacyView> privacy() {
        var privacy = authService.privacy(CurrentUser.requireId());
        return ApiResult.ok(new PrivacyView(privacy.getProfileVisibility(), privacy.isShowOnRanking(),
                privacy.isShowAccounts(), privacy.isShowStudentNo(), privacy.isShowEmail(), privacy.isPublicActivity()));
    }

    @PostMapping("/privacy")
    public ApiResult<PrivacyView> updatePrivacy(@RequestBody PrivacyRequest request, HttpServletRequest http) {
        return ApiResult.ok(authService.updatePrivacy(CurrentUser.requireId(), request,
                http.getRemoteAddr(), http.getHeader("User-Agent")));
    }

    /** 修改密码后所有设备需要重新登录，因此顺带清掉刷新令牌 Cookie。 */
    @PostMapping("/password")
    public ResponseEntity<ApiResult<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                          HttpServletRequest http) {
        authService.changePassword(CurrentUser.requireId(), request.oldPassword(), request.newPassword(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        cookies.clear().toString())
                .body(ApiResult.ok());
    }

    /** 导出本人数据：不含密码、令牌与平台登录态。 */
    @GetMapping("/export")
    public ApiResult<Map<String, Object>> export() {
        return ApiResult.ok(authService.exportMyData(CurrentUser.requireId()));
    }

    /** 注销账号并删除本人数据。要先确认本人（邮箱验证码、密码或 GitHub），再输用户名确认。 */
    @PostMapping("/delete")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResult<Void>> deleteAccount(@Valid @RequestBody DeleteAccountRequest request,
                                                         HttpServletRequest http) {
        authService.lockAccountDeletion(CurrentUser.requireId());
        actions.consume(CurrentUser.requireId(), "DELETE_ACCOUNT", request.actionToken());
        authService.deleteVerifiedAccount(CurrentUser.requireId(), request.confirmUsername(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        cookies.clear().toString())
                .body(ApiResult.ok());
    }

    /** 查看他人档案：按对方的隐私设置裁剪。 */
    @GetMapping("/profile/{userId}")
    public ApiResult<PublicProfileView> publicProfile(@PathVariable Long userId) {
        Long viewerId = CurrentUser.requireId();
        return ApiResult.ok(authService.publicProfile(userId, viewerId, authService.canReadPrivateProfiles(viewerId)));
    }

    @DeleteMapping("/sessions")
    public ApiResult<Integer> revokeAllSessions(HttpServletRequest http) {
        return ApiResult.ok(authService.logoutAll(CurrentUser.requireId(),
                http.getRemoteAddr(), http.getHeader("User-Agent")));
    }
}
