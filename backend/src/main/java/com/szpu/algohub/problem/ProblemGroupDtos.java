package com.szpu.algohub.problem;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class ProblemGroupDtos {

    private ProblemGroupDtos() {
    }

    public record CreateGroupRequest(
            @NotBlank String canonicalKey,
            String title,
            String note,
            /** 创建时一并加入的题目，可为空。 */
            List<Long> problemIds) {
    }

    public record AddMemberRequest(
            @NotNull Long problemId,
            /** SAME_PROBLEM / MIRROR / TRANSLATED */
            String relation) {
    }

    public record MemberView(Long problemId, String platform, String problemKey, String title,
                             String url, String relation, String source, Instant createdAt) {
    }

    public record GroupView(Long id, String canonicalKey, String title, String note,
                            Instant createdAt, List<MemberView> members) {
    }

    /** 题目搜索结果，供管理员建立映射时选择。 */
    public record ProblemSearchItem(Long id, String platform, String problemKey, String title,
                                    String url, String difficultyLabel, Long groupId, String groupKey) {
    }
}
