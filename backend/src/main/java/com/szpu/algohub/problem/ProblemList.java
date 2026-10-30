package com.szpu.algohub.problem;

import java.time.Instant;

public class ProblemList {

    private Long id;
    private String code;
    private String title;
    private String description;
    private String category;
    private String platform;
    /** MANUAL 人工挑选；GENERATED 按规则从题库生成。 */
    private String source = "MANUAL";
    private String generatedRule;
    private boolean visible = true;
    private int sortOrder = 100;
    private Long createdBy;
    /** 归属用户；为空表示管理员维护的系统题单。 */
    private Long ownerId;
    /**
     * PRIVATE 仅作者；SCHOOL 只对 schoolId 这所学校可见；PUBLIC 全站登录用户可见。
     * 系统题单另受 visible 控制（管理员可以隐藏）。
     */
    private String shareScope = "PRIVATE";
    /** SCHOOL 范围对应的学校；学生分享时由服务端按作者的成员记录填写。 */
    private Long schoolId;
    private Instant createdAt;
    private Instant updatedAt;

    // 查询时一并算出的进度字段
    private Integer itemCount;
    private Integer solvedCount;
    /** 列表展示用：作者昵称、我是否收藏、收藏数。 */
    private String ownerName;
    private Boolean favorited;
    private Integer favoriteCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getGeneratedRule() {
        return generatedRule;
    }

    public void setGeneratedRule(String generatedRule) {
        this.generatedRule = generatedRule;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getShareScope() {
        return shareScope;
    }

    public void setShareScope(String shareScope) {
        this.shareScope = shareScope;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public void setSchoolId(Long schoolId) {
        this.schoolId = schoolId;
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

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public Integer getSolvedCount() {
        return solvedCount;
    }

    public void setSolvedCount(Integer solvedCount) {
        this.solvedCount = solvedCount;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public Boolean getFavorited() {
        return favorited;
    }

    public void setFavorited(Boolean favorited) {
        this.favorited = favorited;
    }

    public Integer getFavoriteCount() {
        return favoriteCount;
    }

    public void setFavoriteCount(Integer favoriteCount) {
        this.favoriteCount = favoriteCount;
    }
}
