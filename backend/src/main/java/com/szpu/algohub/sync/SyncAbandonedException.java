package com.szpu.algohub.sync;

/**
 * 本次执行已无权保存结果：任务租约过期被接管、任务已结束，或账号在抓取期间解绑、改了标识。
 * 抛出后保存事务回滚，本次抓到的数据整批放弃；这不是同步失败，不改写任务或账号的失败状态。
 */
public class SyncAbandonedException extends RuntimeException {

    /** 任务仍归本次执行（只是数据作废，例如账号标识变了），可以立即放回队列重跑。 */
    private final boolean releasable;

    public SyncAbandonedException(String message) {
        this(message, false);
    }

    public SyncAbandonedException(String message, boolean releasable) {
        super(message);
        this.releasable = releasable;
    }

    public boolean isReleasable() {
        return releasable;
    }
}
