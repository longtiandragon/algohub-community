package com.szpu.algohub.account;

import java.time.Instant;

public class PlatformAccount {

    private Long id;
    private Long userId;
    private String platform;
    private String platformUid;
    private String handle;
    private String ownershipStatus;
    private String authMode;
    private String syncStatus;
    private boolean supportsHistory;
    private boolean historyComplete;
    private Instant lastSuccessAt;
    private Instant lastAttemptAt;
    private String lastErrorKind;
    private String lastErrorMsg;
    private String note;
    /** 归属变化说明，例如经申诉审核后转为个人关注；只给账号本人看。 */
    private String ownershipNote;
    private Instant createdAt;
    private Instant updatedAt;

    // 连表查询字段
    private String username;
    private String displayName;
    private Boolean hasCredential;

    /** 登录态状态：ACTIVE 可用；INVALID 源站判定已失效（不再用于同步，等待重新设置）；无凭据为 null。 */
    private String credentialStatus;

    // 按平台能力推导的展示字段，不入库
    /** VERIFIED 正式绑定；CLAIMABLE 待认领（可验证）；WATCHING 个人关注。 */
    private String bindingKind;
    private boolean proofSupported;

    // 数据概况（列表接口一次算好，页面不再拿平台名与账号名去别处拼）
    /** 平台统计题数：最近一次成功快照里的数字；从未取得时为 null（不是 0）。 */
    private Integer platformSolvedTotal;
    private Instant platformSolvedAt;
    /** 已收录的题目明细（去重后的通过题数），其中有通过时间的题数，以及牛客 Tracker 来源的题数。 */
    private Integer collectedProblems;
    private Integer timedProblems;
    private Integer trackerProblems;
    /** 各数据项的同步状态，见 account_data_status。 */
    private java.util.List<java.util.Map<String, Object>> dataItems;
    /** 比赛与 Rating：参加场次、计分场次、当前与最高 Rating（没有记录时为 null） */
    private Integer contests;
    private Integer ratedContests;
    private Integer currentRating;
    private Integer maxRating;
    /** 牛客 Tracker：通过题数、其中与竞赛题同题已合并的、题号相同但标题不同另记的 */
    private Integer trackerAccepted;
    private Integer trackerMerged;
    private Integer trackerConflicts;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getPlatformUid() {
        return platformUid;
    }

    public void setPlatformUid(String platformUid) {
        this.platformUid = platformUid;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public String getOwnershipStatus() {
        return ownershipStatus;
    }

    public void setOwnershipStatus(String ownershipStatus) {
        this.ownershipStatus = ownershipStatus;
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        this.authMode = authMode;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public boolean isSupportsHistory() {
        return supportsHistory;
    }

    public void setSupportsHistory(boolean supportsHistory) {
        this.supportsHistory = supportsHistory;
    }

    public boolean isHistoryComplete() {
        return historyComplete;
    }

    public void setHistoryComplete(boolean historyComplete) {
        this.historyComplete = historyComplete;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public String getLastErrorKind() {
        return lastErrorKind;
    }

    public void setLastErrorKind(String lastErrorKind) {
        this.lastErrorKind = lastErrorKind;
    }

    public String getLastErrorMsg() {
        return lastErrorMsg;
    }

    public void setLastErrorMsg(String lastErrorMsg) {
        this.lastErrorMsg = lastErrorMsg;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Boolean getHasCredential() {
        return hasCredential;
    }

    public void setHasCredential(Boolean hasCredential) {
        this.hasCredential = hasCredential;
    }

    public String getOwnershipNote() {
        return ownershipNote;
    }

    public void setOwnershipNote(String ownershipNote) {
        this.ownershipNote = ownershipNote;
    }

    public String getCredentialStatus() {
        return credentialStatus;
    }

    public void setCredentialStatus(String credentialStatus) {
        this.credentialStatus = credentialStatus;
    }

    public Integer getPlatformSolvedTotal() {
        return platformSolvedTotal;
    }

    public void setPlatformSolvedTotal(Integer platformSolvedTotal) {
        this.platformSolvedTotal = platformSolvedTotal;
    }

    public Instant getPlatformSolvedAt() {
        return platformSolvedAt;
    }

    public void setPlatformSolvedAt(Instant platformSolvedAt) {
        this.platformSolvedAt = platformSolvedAt;
    }

    public Integer getCollectedProblems() {
        return collectedProblems;
    }

    public void setCollectedProblems(Integer collectedProblems) {
        this.collectedProblems = collectedProblems;
    }

    public Integer getTimedProblems() {
        return timedProblems;
    }

    public void setTimedProblems(Integer timedProblems) {
        this.timedProblems = timedProblems;
    }

    public Integer getTrackerProblems() {
        return trackerProblems;
    }

    public void setTrackerProblems(Integer trackerProblems) {
        this.trackerProblems = trackerProblems;
    }

    public java.util.List<java.util.Map<String, Object>> getDataItems() {
        return dataItems;
    }

    public void setDataItems(java.util.List<java.util.Map<String, Object>> dataItems) {
        this.dataItems = dataItems;
    }

    public String getBindingKind() {
        return bindingKind;
    }

    public void setBindingKind(String bindingKind) {
        this.bindingKind = bindingKind;
    }

    public boolean isProofSupported() {
        return proofSupported;
    }

    public void setProofSupported(boolean proofSupported) {
        this.proofSupported = proofSupported;
    }

    public Integer getContests() {
        return contests;
    }

    public void setContests(Integer contests) {
        this.contests = contests;
    }

    public Integer getRatedContests() {
        return ratedContests;
    }

    public void setRatedContests(Integer ratedContests) {
        this.ratedContests = ratedContests;
    }

    public Integer getCurrentRating() {
        return currentRating;
    }

    public void setCurrentRating(Integer currentRating) {
        this.currentRating = currentRating;
    }

    public Integer getMaxRating() {
        return maxRating;
    }

    public void setMaxRating(Integer maxRating) {
        this.maxRating = maxRating;
    }

    public Integer getTrackerAccepted() {
        return trackerAccepted;
    }

    public void setTrackerAccepted(Integer trackerAccepted) {
        this.trackerAccepted = trackerAccepted;
    }

    public Integer getTrackerMerged() {
        return trackerMerged;
    }

    public void setTrackerMerged(Integer trackerMerged) {
        this.trackerMerged = trackerMerged;
    }

    public Integer getTrackerConflicts() {
        return trackerConflicts;
    }

    public void setTrackerConflicts(Integer trackerConflicts) {
        this.trackerConflicts = trackerConflicts;
    }
}
