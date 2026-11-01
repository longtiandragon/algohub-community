package com.szpu.algohub.platform;

import java.util.List;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.PlatformDtos.FetchedContest;
import com.szpu.algohub.platform.PlatformDtos.OwnershipProof;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.ResolvedIdentity;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;

/**
 * 平台适配器。平台差异保留在适配层，不散落到统计与排行榜业务中。
 */
public interface PlatformAdapter {

    /** 公开证明字段读取失败时的可展示原因；不影响账号搜索结果。 */
    String OWNERSHIP_READ_ERROR = "ownershipReadError";

    Platform platform();

    PlatformCapability capability();

    /**
     * 用平台返回的资料确认账号存在并取得稳定 UID。
     *
     * @param input      用户填写的 handle、UID 或主页链接
     * @param credential 明文登录态，无则为 null
     */
    ResolvedIdentity resolveIdentity(String input, String credential);

    /**
     * 公开资料归属证明方式。返回 null 表示该平台暂时无法证明归属：
     * 账号只能添加为个人关注，不参与身份认证与校内排行。
     */
    default OwnershipProof ownershipProof() {
        return null;
    }

    /**
     * 重新查询已登记账号时交给 {@link #resolveIdentity} 的标识。默认用 handle；
     * 只能按数字 ID 查询、昵称还会重名的平台（码蹄集）覆盖为 UID。
     */
    default String lookupKey(String platformUid, String handle) {
        return handle;
    }

    /** 公开资料里有没有头像。没有的平台（杭电、QOJ）在选头像时写「这个平台不提供头像」。 */
    default boolean avatarSupported() {
        return false;
    }

    /**
     * 从 {@link #resolveIdentity} 的结果里认出头像地址：只接受 https，没有或是平台给的默认头像时返回 null。
     * 默认读 extra 里的 {@link Avatars#KEY}（适配器解析资料时已经排除了默认图）；
     * 默认头像和自传头像同一个地址、要看图片本身才分得清的平台（洛谷）覆盖它，会多发一次请求，取不到时抛异常。
     */
    default String avatarOf(ResolvedIdentity identity) {
        return identity == null || identity.extra() == null ? null : Avatars.https(identity.extra().get(Avatars.KEY));
    }

    /**
     * 提交历史永远补不全的平台（只公开最近若干条）返回一句说明，否则返回 null。
     * 这类平台每轮同步都一并取总量、保留总量的观察增量，数据项上写这句说明而不是"回填中"。
     */
    default String partialHistoryNote() {
        return null;
    }

    /** 是否提供比赛与 Rating 记录（参加过的比赛、名次、Rating 变化）。 */
    default boolean contestHistorySupported() {
        return false;
    }

    /** 参加过的比赛与 Rating 变化。一场都没参加过时返回空列表（真实的零场），取不到时抛异常。 */
    default List<com.szpu.algohub.platform.PlatformDtos.RatedContest> fetchContestHistory(SyncContext context) {
        throw new ApiException(ErrorKind.UNSUPPORTED,
                platform().getDisplayName() + " 暂不支持比赛与 Rating 记录");
    }

    default SubmissionPage fetchSubmissions(SyncContext context) {
        throw new ApiException(ErrorKind.UNSUPPORTED,
                platform().getDisplayName() + " 暂不支持逐条提交同步");
    }

    default TotalsSnapshot fetchTotals(SyncContext context) {
        throw new ApiException(ErrorKind.UNSUPPORTED,
                platform().getDisplayName() + " 暂不支持总量快照");
    }

    /**
     * 平台公开的题库清单。默认不支持：只有官方提供全量题目接口的平台才实现，
     * 不用抓取到的零散题目冒充完整题库。
     */
    default List<com.szpu.algohub.platform.PlatformDtos.CatalogProblem> fetchProblemCatalog() {
        return List.of();
    }

    /** 是否提供公开题库接口。默认否，实现了 fetchProblemCatalog 的适配器覆盖为 true。 */
    default boolean fetchProblemCatalogSupported() {
        return false;
    }

    default List<FetchedContest> fetchContests() {
        return List.of();
    }
}
