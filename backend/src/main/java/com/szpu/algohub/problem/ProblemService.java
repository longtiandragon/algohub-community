package com.szpu.algohub.problem;

import org.springframework.stereotype.Service;

import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;

@Service
public class ProblemService {

    private final ProblemMapper problemMapper;

    public ProblemService(ProblemMapper problemMapper) {
        this.problemMapper = problemMapper;
    }

    /**
     * 写入题目及其原始标签，返回题目主键。标签保存原始值与来源，映射在统计时进行。
     */
    public Long upsert(Platform platform, FetchedProblem fetched) {
        Problem problem = new Problem();
        problem.setPlatform(platform.name());
        problem.setProblemKey(fetched.problemKey());
        problem.setTitle(fetched.title());
        problem.setUrl(fetched.url());
        problem.setDifficultyRaw(fetched.difficultyRaw());
        problem.setDifficultyLabel(fetched.difficultyLabel());
        problem.setDifficultySource(fetched.difficultySource());
        // 提交同步也维护数值列，避免显示标签已变而难度筛选仍用旧值。
        if (fetched.difficultyRaw() != null && fetched.difficultyRaw().matches("-?\\d+")) {
            try { problem.setDifficultyValue(Integer.valueOf(fetched.difficultyRaw())); }
            catch (NumberFormatException ignored) { /* 非 int 数值保留原文 */ }
        }
        problem.setTagsComplete(fetched.tagsComplete());
        problemMapper.upsert(problem);
        Long id = problem.getId();
        if (id == null) {
            Problem existing = problemMapper.find(platform.name(), fetched.problemKey());
            id = existing == null ? null : existing.getId();
        }
        if (id != null && fetched.tags() != null) {
            String source = "THIRD_PARTY".equals(fetched.difficultySource()) ? "THIRD_PARTY" : "OFFICIAL";
            for (String tag : fetched.tags()) {
                if (tag != null && !tag.isBlank()) {
                    problemMapper.insertTag(id, tag.trim(), source);
                }
            }
        }
        return id;
    }
}
