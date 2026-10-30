package com.szpu.algohub.problem;

public class Problem {

    private Long id;
    private String platform;
    private String problemKey;
    private String title;
    private String url;
    private String difficultyRaw;
    private String difficultyLabel;
    private String difficultySource;
    private boolean tagsComplete;
    private Integer difficultyValue;
    private Integer solvedCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getProblemKey() {
        return problemKey;
    }

    public void setProblemKey(String problemKey) {
        this.problemKey = problemKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDifficultyRaw() {
        return difficultyRaw;
    }

    public void setDifficultyRaw(String difficultyRaw) {
        this.difficultyRaw = difficultyRaw;
    }

    public String getDifficultyLabel() {
        return difficultyLabel;
    }

    public void setDifficultyLabel(String difficultyLabel) {
        this.difficultyLabel = difficultyLabel;
    }

    public String getDifficultySource() {
        return difficultySource;
    }

    public void setDifficultySource(String difficultySource) {
        this.difficultySource = difficultySource;
    }

    public Integer getDifficultyValue() {
        return difficultyValue;
    }

    public void setDifficultyValue(Integer difficultyValue) {
        this.difficultyValue = difficultyValue;
    }

    public Integer getSolvedCount() {
        return solvedCount;
    }

    public void setSolvedCount(Integer solvedCount) {
        this.solvedCount = solvedCount;
    }

    public boolean isTagsComplete() {
        return tagsComplete;
    }

    public void setTagsComplete(boolean tagsComplete) {
        this.tagsComplete = tagsComplete;
    }
}
