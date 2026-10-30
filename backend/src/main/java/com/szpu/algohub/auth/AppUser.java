package com.szpu.algohub.auth;

import java.time.Instant;

public class AppUser {

    private Integer enrollmentYear;
    private String className;
    private String studentNo;
    private boolean onboardingCompleted;
    private boolean nicknameNeedsReview;
    public boolean isNicknameNeedsReview() { return nicknameNeedsReview; }
    public void setNicknameNeedsReview(boolean value) { nicknameNeedsReview = value; }
    public Integer getEnrollmentYear() { return enrollmentYear; }
    public void setEnrollmentYear(Integer value) { enrollmentYear=value; }
    public String getClassName() { return className; }
    public void setClassName(String value) { className=value; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String value) { studentNo=value; }
    public boolean isOnboardingCompleted() { return onboardingCompleted; }
    public void setOnboardingCompleted(boolean value) { onboardingCompleted=value; }
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String email;
    private String role;
    private String status;
    private String avatarUrl;
    private String bio;
    private Instant lastLoginAt;
    /** 签发时间早于该时刻的访问令牌一律失效（改密、退出所有设备时推进）。 */
    private Instant tokensValidFrom;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Instant getTokensValidFrom() {
        return tokensValidFrom;
    }

    public void setTokensValidFrom(Instant tokensValidFrom) {
        this.tokensValidFrom = tokensValidFrom;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
