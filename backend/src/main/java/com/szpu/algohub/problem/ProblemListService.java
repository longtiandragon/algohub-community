package com.szpu.algohub.problem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.school.SchoolService;

/**
 * 题单 / 训练计划。
 *
 * 完成进度实时按已通过记录计算，不保存“已完成”标记；
 * 自动生成的题单会记录生成规则，方便复现，也让使用者知道它不是人工精选。
 */
@Service
public class ProblemListService {

    private final ProblemListMapper mapper;
    private final RecommendationMapper recommendationMapper;
    private final SchoolService schoolService;

    public ProblemListService(ProblemListMapper mapper, RecommendationMapper recommendationMapper,
                              SchoolService schoolService) {
        this.mapper = mapper;
        this.recommendationMapper = recommendationMapper;
        this.schoolService = schoolService;
    }

    /**
     * @param shareScope PRIVATE 仅自己 / SCHOOL 指定学校 / PUBLIC 全站
     * @param schoolId   只有管理员建系统题单时才采用：学生分享到学校时一律用作者自己的学校
     */
    public record CreateListRequest(String code, String title, String description, String category,
                                    String platform, Integer sortOrder, String shareScope, Long schoolId) {
        public CreateListRequest(String code, String title, String description, String category,
                                 String platform, Integer sortOrder, String shareScope) {
            this(code, title, description, category, platform, sortOrder, shareScope, null);
        }
    }

    public record GenerateRequest(String code, String title, String description, String platform,
                                  Integer minDifficulty, Integer maxDifficulty, Integer size,
                                  String category, Integer sortOrder,
                                  /** 按统一标签分类筛选，如「动态规划」；留空表示不限分类。 */
                                  String tagCategory,
                                  /** 分享范围：PRIVATE / SCHOOL / PUBLIC。 */
                                  String shareScope,
                                  /** 只有管理员建系统题单时才采用，见 CreateListRequest。 */
                                  Long schoolId) {
        public GenerateRequest(String code, String title, String description, String platform,
                               Integer minDifficulty, Integer maxDifficulty, Integer size,
                               String category, Integer sortOrder, String tagCategory, String shareScope) {
            this(code, title, description, platform, minDifficulty, maxDifficulty, size, category, sortOrder,
                    tagCategory, shareScope, null);
        }
    }

    /**
     * @param scope ALL（默认）/ MINE 我建的 / FAVORITE 我收藏的 / SYSTEM 系统题单
     */
    public List<ProblemList> listAll(Long userId, boolean includeHidden, String scope) {
        String normalized = scope == null || scope.isBlank() ? "ALL" : scope.trim().toUpperCase();
        if (!List.of("ALL", "MINE", "FAVORITE", "SYSTEM").contains(normalized)) {
            throw ApiException.validation("未知的题单范围：" + scope);
        }
        // 学校只按服务端成员记录确定
        return mapper.findAll(userId, schoolService.approvedSchoolId(userId), includeHidden, normalized);
    }

    public Map<String, Object> detail(Long listId, Long userId) {
        ProblemList list = mapper.findByIdForUser(listId, userId);
        if (list == null) {
            throw ApiException.notFound("题单不存在");
        }
        List<Map<String, Object>> items = mapper.findItems(listId, userId);
        for (Map<String, Object> item : items) {
            Long problemId = ((Number) item.get("problemId")).longValue();
            item.put("tags", recommendationMapper.problemTags(problemId, String.valueOf(item.get("platform"))));
        }
        long solved = items.stream().filter(i -> Boolean.TRUE.equals(i.get("solvedByMe"))).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("items", items);
        result.put("itemCount", items.size());
        result.put("solvedCount", solved);
        result.put("note", "完成进度按你已同步的通过记录实时计算，不单独保存完成标记；"
                + "未同步的记录不会计入。"
                + ("GENERATED".equals(list.getSource())
                        ? "本题单按页首的生成规则从题库自动选出，不是人工精选。"
                        : ""));
        return result;
    }

    /** 题单完成榜：只列查看者本校的同学，全站题单也一样。 */
    public Map<String, Object> leaderboard(Long listId, Long viewerId, int limit) {
        ProblemList list = mapper.findById(listId);
        if (list == null) {
            throw ApiException.notFound("题单不存在");
        }
        Long schoolId = schoolService.approvedSchoolId(viewerId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("listId", listId);
        result.put("title", list.getTitle());
        result.put("itemCount", mapper.findItems(listId, 0L).size());
        result.put("rows", schoolId == null ? List.of()
                : mapper.leaderboard(schoolId, listId, Math.min(Math.max(limit, 1), 100)));
        result.put("note", schoolId == null ? "请先选择学校后查看校内完成榜。"
                : "只列本校同学已验证归属的账号，且排除了在隐私设置中关闭排行榜展示或把档案设为仅自己可见的同学。");
        return result;
    }

    // ---------- 创建与编辑 ----------

    /** 每人最多能建多少个题单，避免无意义刷量。 */
    private static final int MAX_LISTS_PER_USER = 50;

    /**
     * 建题单。
     *
     * @param asSystem true 表示建管理员维护的系统题单（owner 为空，默认全站可见，也可指定学校）；
     *                 false 表示学生自建，默认仅自己可见，可分享到本校或全站。
     */
    @Transactional
    public ProblemList create(Long userId, boolean asSystem, CreateListRequest request) {
        if (!StringUtils.hasText(request.title())) {
            throw ApiException.validation("请填写题单标题");
        }
        if (!asSystem && mapper.countOwned(userId) >= MAX_LISTS_PER_USER) {
            throw ApiException.conflict("每人最多创建 " + MAX_LISTS_PER_USER + " 个题单，请先删除不用的");
        }
        String code = buildCode(userId, asSystem, request.code(), "");

        ProblemList list = new ProblemList();
        list.setCode(code);
        list.setTitle(request.title().trim());
        list.setDescription(trimOrNull(request.description()));
        list.setCategory(trimOrNull(request.category()));
        list.setPlatform(request.platform() == null ? null : Platform.parse(request.platform()).name());
        list.setSource("MANUAL");
        list.setVisible(true);
        list.setSortOrder(request.sortOrder() == null ? 100 : request.sortOrder());
        list.setCreatedBy(userId);
        list.setOwnerId(asSystem ? null : userId);
        applyScope(list, request.shareScope(), request.schoolId());
        mapper.insertList(list);
        return mapper.findById(list.getId());
    }

    /**
     * 设置分享范围与对应学校。学生题单分享到 SCHOOL 时学校一律取作者自己的成员记录，
     * 不采用请求里的学校编号；系统题单（管理员维护）可以指定学校，默认全站。
     */
    private void applyScope(ProblemList list, String requestedScope, Long requestedSchoolId) {
        boolean system = list.getOwnerId() == null;
        String scope = normalizeShareScope(requestedScope, system ? "PUBLIC" : "PRIVATE");
        if (system && "PRIVATE".equals(scope)) {
            throw ApiException.validation("系统题单只能面向全站或指定学校；不想让人看到可以隐藏");
        }
        Long schoolId = null;
        if ("SCHOOL".equals(scope)) {
            if (system) {
                schoolId = requestedSchoolId != null ? requestedSchoolId : list.getSchoolId();
                if (schoolService.findSchool(schoolId) == null) {
                    throw ApiException.validation("面向学校的系统题单需要选择一所有效的学校");
                }
            } else {
                schoolId = schoolService.approvedSchoolId(list.getOwnerId());
                if (schoolId == null) {
                    throw ApiException.validation("还没有加入学校，暂时不能分享到本校；可以先选择学校或分享到全站");
                }
            }
        }
        list.setShareScope(scope);
        list.setSchoolId(schoolId);
    }

    /**
     * 生成题单标识。系统题单要求管理员自己指定；学生自建时自动加 U{用户} 前缀，
     * 既省得自己想标识，也不会和系统题单撞号。
     */
    private String buildCode(Long userId, boolean asSystem, String requested, String prefix) {
        String code = asSystem
                ? requireCode(requested)
                : (StringUtils.hasText(requested)
                        ? requireCode("U" + userId + "-" + requested)
                        : "U" + userId + "-" + prefix
                                + Long.toString(System.currentTimeMillis(), 36).toUpperCase());
        if (mapper.findByCode(code) != null) {
            throw ApiException.conflict("已存在相同标识的题单：" + code);
        }
        return code;
    }

    private String normalizeShareScope(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String scope = value.trim().toUpperCase();
        if (!List.of("PRIVATE", "SCHOOL", "PUBLIC").contains(scope)) {
            throw ApiException.validation("分享范围只能是 PRIVATE（仅自己）、SCHOOL（本校）或 PUBLIC（全站）");
        }
        return scope;
    }

    /** 编辑权限：系统题单只有管理员能改，学生题单只有本人（或管理员）能改。 */
    public ProblemList requireEditable(Long listId, Long userId, boolean admin) {
        ProblemList list = mapper.findById(listId);
        if (list == null) {
            throw ApiException.notFound("题单不存在");
        }
        if (list.getOwnerId() == null) {
            if (!admin) {
                throw ApiException.forbidden("系统题单只能由管理员维护");
            }
        } else if (!admin && !list.getOwnerId().equals(userId)) {
            throw ApiException.forbidden("只能修改自己创建的题单");
        }
        return list;
    }

    /**
     * 查看权限：作者与管理员总能看；PUBLIC 全站可见；SCHOOL 只对同一所学校可见（按服务端成员记录判断）；
     * PRIVATE 只有作者。隐藏的系统题单只有管理员能看。
     */
    public ProblemList requireReadable(Long listId, Long userId, boolean admin) {
        ProblemList list = mapper.findById(listId);
        if (list == null) {
            throw ApiException.notFound("题单不存在");
        }
        boolean mine = list.getOwnerId() != null && list.getOwnerId().equals(userId);
        boolean hiddenSystem = list.getOwnerId() == null && !list.isVisible();
        boolean inScope = switch (list.getShareScope()) {
            case "PUBLIC" -> true;
            case "SCHOOL" -> list.getSchoolId() != null
                    && list.getSchoolId().equals(schoolService.approvedSchoolId(userId));
            default -> false;
        };
        if (!(admin || mine || (!hiddenSystem && inScope))) {
            throw ApiException.forbidden("该题单未公开");
        }
        return list;
    }

    @Transactional
    public Map<String, Object> setFavorite(Long userId, Long listId, boolean favorite, boolean admin) {
        requireReadable(listId, userId, admin);
        if (favorite) {
            mapper.addFavorite(userId, listId);
        } else {
            mapper.removeFavorite(userId, listId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("listId", listId);
        result.put("favorited", favorite);
        return result;
    }

    /**
     * 按规则从题库生成题单：指定平台与难度区间，按平台公开通过人数取前若干题。
     * 生成规则会写进题单，界面上明确标注「自动生成」。
     */
    @Transactional
    public Map<String, Object> generate(Long userId, boolean asSystem, GenerateRequest request) {
        if (!asSystem && mapper.countOwned(userId) >= MAX_LISTS_PER_USER) {
            throw ApiException.conflict("每人最多创建 " + MAX_LISTS_PER_USER + " 个题单，请先删除不用的");
        }
        String code = buildCode(userId, asSystem, request.code(), "G");
        Platform platform = Platform.parse(request.platform());
        int min = request.minDifficulty() == null ? 800 : request.minDifficulty();
        int max = request.maxDifficulty() == null ? 1200 : request.maxDifficulty();
        int size = Math.min(Math.max(request.size() == null ? 30 : request.size(), 1), 200);
        if (min > max) {
            throw ApiException.validation("难度区间的下限不能大于上限");
        }
        if (recommendationMapper.catalogSize(platform.name()) == 0) {
            throw ApiException.validation(platform.getDisplayName() + " 的题库尚未同步，无法生成题单");
        }

        String tagCategory = trimOrNull(request.tagCategory());
        List<Long> problemIds = mapper.pickByRule(platform.name(), min, max, tagCategory, size);
        if (problemIds.isEmpty()) {
            throw ApiException.validation(tagCategory == null
                    ? "该难度区间内没有题目，请调整区间"
                    : "该难度区间内没有「" + tagCategory + "」分类的题目，请换个分类或放宽区间");
        }

        String rule = platform.name() + " 难度 " + min + "–" + max
                + (tagCategory == null ? "" : "，分类 " + tagCategory)
                + "，按平台公开通过人数取前 " + size + " 题";
        ProblemList list = new ProblemList();
        list.setCode(code);
        list.setTitle(StringUtils.hasText(request.title()) ? request.title().trim()
                : platform.getDisplayName() + " " + min + "–" + max + " 练习 " + problemIds.size() + " 题");
        list.setDescription(trimOrNull(request.description()));
        list.setCategory(StringUtils.hasText(request.category()) ? request.category().trim() : "自动生成");
        list.setPlatform(platform.name());
        list.setSource("GENERATED");
        list.setGeneratedRule(rule);
        list.setVisible(true);
        list.setSortOrder(request.sortOrder() == null ? 100 : request.sortOrder());
        list.setCreatedBy(userId);
        list.setOwnerId(asSystem ? null : userId);
        applyScope(list, request.shareScope(), request.schoolId());
        mapper.insertList(list);

        int position = 0;
        for (Long problemId : problemIds) {
            mapper.insertItem(list.getId(), problemId, ++position, null);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", mapper.findById(list.getId()));
        result.put("added", problemIds.size());
        result.put("rule", rule);
        result.put("note", "题目按规则从已同步题库中选出，界面会标注为自动生成，不是人工精选。");
        return result;
    }

    /** 一次请求最多处理的题目数：更多的题目分批提交。 */
    public static final int MAX_ITEMS_PER_REQUEST = 500;

    private static void requireBatch(List<Long> problemIds) {
        if (problemIds == null) {
            throw ApiException.validation("缺少题目列表");
        }
        if (problemIds.size() > MAX_ITEMS_PER_REQUEST) {
            throw ApiException.validation("一次最多处理 " + MAX_ITEMS_PER_REQUEST + " 道题，请分批操作");
        }
    }

    @Transactional
    public Map<String, Object> addItems(Long listId, List<Long> problemIds, String note) {
        requireBatch(problemIds);
        ProblemList list = mapper.findById(listId);
        if (list == null) {
            throw ApiException.notFound("题单不存在");
        }
        int position = mapper.maxPosition(listId);
        int added = 0;
        List<Long> skipped = new ArrayList<>();
        for (Long problemId : problemIds) {
            int rows = mapper.insertItem(listId, problemId, ++position, note);
            if (rows > 0) {
                added++;
            } else {
                // 已在题单中，保持原有顺序不动
                skipped.add(problemId);
                position--;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", added);
        result.put("skipped", skipped);
        result.put("itemCount", mapper.findItems(listId, 0L).size());
        return result;
    }

    @Transactional
    public void removeItem(Long listId, Long problemId) {
        if (mapper.deleteItem(listId, problemId) == 0) {
            throw ApiException.notFound("该题不在题单中");
        }
    }

    /** 重排：按给定顺序重新写 position，未列出的题保持在后面。 */
    @Transactional
    public void reorder(Long listId, List<Long> problemIdsInOrder) {
        requireBatch(problemIdsInOrder);
        int position = 0;
        for (Long problemId : problemIdsInOrder) {
            mapper.updatePosition(listId, problemId, ++position);
        }
    }

    @Transactional
    public ProblemList update(Long listId, CreateListRequest request, Boolean visible) {
        ProblemList list = mapper.findById(listId);
        if (list == null) {
            throw ApiException.notFound("题单不存在");
        }
        if (StringUtils.hasText(request.shareScope()) || request.schoolId() != null) {
            applyScope(list, StringUtils.hasText(request.shareScope()) ? request.shareScope() : list.getShareScope(),
                    request.schoolId());
        }
        if (StringUtils.hasText(request.title())) {
            list.setTitle(request.title().trim());
        }
        list.setDescription(trimOrNull(request.description()));
        list.setCategory(trimOrNull(request.category()));
        if (request.sortOrder() != null) {
            list.setSortOrder(request.sortOrder());
        }
        if (visible != null) {
            list.setVisible(visible);
        }
        mapper.updateList(list);
        return mapper.findById(listId);
    }

    @Transactional
    public void delete(Long listId) {
        if (mapper.deleteList(listId) == 0) {
            throw ApiException.notFound("题单不存在");
        }
    }

    private String requireCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw ApiException.validation("请填写题单标识（code）");
        }
        String normalized = code.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9_-]{2,64}")) {
            throw ApiException.validation("题单标识只能包含字母、数字、下划线和连字符");
        }
        return normalized;
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
