package com.szpu.algohub.auth;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** 当前请求的登录用户。携带访问令牌的 jti 与有效期，登出时用于拉黑该令牌。 */
public class LoginUser implements UserDetails {

    private final Long id;
    private final String username;
    private final String displayName;
    private final String passwordHash;
    private final String role;
    private final boolean enabled;
    private final String tokenId;
    private final Instant tokenIssuedAt;
    private final Instant tokenExpiresAt;

    public LoginUser(AppUser user) {
        this(user, null, null, null);
    }

    public LoginUser(AppUser user, String tokenId, Instant tokenIssuedAt, Instant tokenExpiresAt) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.displayName = user.getDisplayName();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
        this.enabled = "ACTIVE".equals(user.getStatus());
        this.tokenId = tokenId;
        this.tokenIssuedAt = tokenIssuedAt;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRole() {
        return role;
    }

    public String getTokenId() {
        return tokenId;
    }

    public Instant getTokenIssuedAt() {
        return tokenIssuedAt;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return enabled;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
