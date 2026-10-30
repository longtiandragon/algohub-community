package com.szpu.algohub.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static LoginUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser user)) {
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "请先登录");
        }
        return user;
    }

    public static Long requireId() {
        return require().getId();
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
