package com.szpu.algohub.auth.identity;

import java.time.Instant;

/**
 * 隐私设置。默认最小公开：资料仅校内成员可见，学号与邮箱默认不公开。
 */
public class UserPrivacy {

    private Long userId;
    /** PUBLIC 任何访客可见；SCHOOL 仅已通过审核的校内成员可见；PRIVATE 仅本人与管理员可见。 */
    private String profileVisibility = "SCHOOL";
    private boolean showOnRanking = true;
    private boolean showAccounts = true;
    private boolean showStudentNo = false;
    private boolean showEmail = false;
    private boolean publicActivity = true;
    public boolean isPublicActivity() { return publicActivity; }
    public void setPublicActivity(boolean value) { publicActivity = value; }
    private Instant updatedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getProfileVisibility() {
        return profileVisibility;
    }

    public void setProfileVisibility(String profileVisibility) {
        this.profileVisibility = profileVisibility;
    }

    public boolean isShowOnRanking() {
        return showOnRanking;
    }

    public void setShowOnRanking(boolean showOnRanking) {
        this.showOnRanking = showOnRanking;
    }

    public boolean isShowAccounts() {
        return showAccounts;
    }

    public void setShowAccounts(boolean showAccounts) {
        this.showAccounts = showAccounts;
    }

    public boolean isShowStudentNo() {
        return showStudentNo;
    }

    public void setShowStudentNo(boolean showStudentNo) {
        this.showStudentNo = showStudentNo;
    }

    public boolean isShowEmail() {
        return showEmail;
    }

    public void setShowEmail(boolean showEmail) {
        this.showEmail = showEmail;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
