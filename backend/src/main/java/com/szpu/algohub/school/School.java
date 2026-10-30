package com.szpu.algohub.school;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class School {

    private Long id;
    private String name;
    private String shortName;
    /** 用邀请码申请加入学校要用它，不能随学校信息返回给前端 */
    @JsonIgnore
    private String inviteCode;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
