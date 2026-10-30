package com.szpu.algohub.problem;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.problem.ProblemGroupDtos.CreateGroupRequest;
import com.szpu.algohub.problem.ProblemGroupDtos.GroupView;
import com.szpu.algohub.problem.ProblemGroupDtos.MemberView;
import com.szpu.algohub.problem.ProblemGroupDtos.ProblemSearchItem;

/**
 * 跨平台题目映射。只接受人工确认的映射：同一来源的复用题、跨平台搬运题需要管理员明确指定，
 * 不提供按题名自动匹配，避免把不同题目合并成一题。
 *
 * 映射只影响“去重后的唯一题量”这一项展示，不改变各平台原始通过数，也不改写已入库的提交记录。
 */
@Service
public class ProblemGroupService {

    private final ProblemGroupMapper groupMapper;
    private final ProblemMapper problemMapper;

    public ProblemGroupService(ProblemGroupMapper groupMapper, ProblemMapper problemMapper) {
        this.groupMapper = groupMapper;
        this.problemMapper = problemMapper;
    }

    public List<ProblemGroup> listGroups(int limit) {
        return groupMapper.listGroups(limit);
    }

    public GroupView getGroup(Long id) {
        ProblemGroup group = groupMapper.findGroup(id);
        if (group == null) {
            throw ApiException.notFound("题目组不存在");
        }
        return toView(group);
    }

    @Transactional
    public GroupView create(Long adminId, CreateGroupRequest request) {
        String key = request.canonicalKey().trim();
        if (groupMapper.findGroupByKey(key) != null) {
            throw ApiException.conflict("已存在相同规范标识的题目组：" + key);
        }
        ProblemGroup group = new ProblemGroup();
        group.setCanonicalKey(key);
        group.setTitle(StringUtils.hasText(request.title()) ? request.title().trim() : null);
        group.setNote(StringUtils.hasText(request.note()) ? request.note().trim() : null);
        group.setCreatedBy(adminId);
        groupMapper.insertGroup(group);

        if (request.problemIds() != null) {
            for (Long problemId : request.problemIds()) {
                addMember(adminId, group.getId(), problemId, "SAME_PROBLEM");
            }
        }
        return toView(groupMapper.findGroup(group.getId()));
    }

    @Transactional
    public GroupView addMember(Long adminId, Long groupId, Long problemId, String relation) {
        ProblemGroup group = groupMapper.findGroup(groupId);
        if (group == null) {
            throw ApiException.notFound("题目组不存在");
        }
        Problem problem = problemMapper.findById(problemId);
        if (problem == null) {
            throw ApiException.notFound("题目不存在：" + problemId);
        }
        Long existingGroup = groupMapper.findGroupIdByProblem(problemId);
        if (existingGroup != null) {
            if (existingGroup.equals(groupId)) {
                return toView(group);
            }
            // 一题只能属于一个组：先移出原组由管理员显式操作，避免静默改变已有口径
            throw ApiException.conflict("该题目已属于其他题目组（id=" + existingGroup + "），请先移除后再加入");
        }
        String normalized = normalizeRelation(relation);
        groupMapper.insertMember(groupId, problemId, normalized, adminId);
        return toView(group);
    }

    private String normalizeRelation(String relation) {
        if (!StringUtils.hasText(relation)) {
            return "SAME_PROBLEM";
        }
        String value = relation.trim().toUpperCase();
        return switch (value) {
            case "SAME_PROBLEM", "MIRROR", "TRANSLATED" -> value;
            default -> throw ApiException.validation("未知的映射关系：" + relation);
        };
    }

    @Transactional
    public void removeMember(Long problemId) {
        if (groupMapper.deleteMemberByProblem(problemId) == 0) {
            throw ApiException.notFound("该题目没有映射记录");
        }
    }

    @Transactional
    public void deleteGroup(Long groupId) {
        if (groupMapper.deleteGroup(groupId) == 0) {
            throw ApiException.notFound("题目组不存在");
        }
    }

    public List<ProblemSearchItem> searchProblems(String platform, String keyword, int limit) {
        return groupMapper.searchProblems(
                        StringUtils.hasText(platform) ? platform : null,
                        StringUtils.hasText(keyword) ? com.szpu.algohub.common.SqlLike.escape(keyword.trim()) : null,
                        limit).stream()
                .map(row -> new ProblemSearchItem(
                        ((Number) row.get("id")).longValue(),
                        (String) row.get("platform"),
                        (String) row.get("problemKey"),
                        (String) row.get("title"),
                        (String) row.get("url"),
                        (String) row.get("difficultyLabel"),
                        row.get("groupId") == null ? null : ((Number) row.get("groupId")).longValue(),
                        (String) row.get("groupKey")))
                .toList();
    }

    public Map<String, Object> coverage() {
        return groupMapper.mappingCoverage();
    }

    /** JDBC 对 timestamptz 的映射类型随驱动而异，这里统一转换为 Instant。 */
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return null;
    }

    private GroupView toView(ProblemGroup group) {
        List<MemberView> members = groupMapper.listMembers(group.getId()).stream()
                .map(row -> new MemberView(
                        ((Number) row.get("problemId")).longValue(),
                        (String) row.get("platform"),
                        (String) row.get("problemKey"),
                        (String) row.get("title"),
                        (String) row.get("url"),
                        (String) row.get("relation"),
                        (String) row.get("source"),
                        toInstant(row.get("createdAt"))))
                .toList();
        return new GroupView(group.getId(), group.getCanonicalKey(), group.getTitle(), group.getNote(),
                group.getCreatedAt(), members);
    }
}
