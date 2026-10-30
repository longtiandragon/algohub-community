package com.szpu.algohub.social;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.ApiException;

@Service
public class SocialService {
    private final JdbcTemplate db;
    private final AuthService auth;
    public SocialService(JdbcTemplate db, AuthService auth) { this.db=db; this.auth=auth; }
    public record Template(String code,String text) {}
    public static final List<Template> TEMPLATES=List.of(
        new Template("TRAIN","来加训吧，一起再攻克一道题！"),
        new Template("TODAY","今天写题了吗？一起完成今天的小目标吧！"),
        new Template("TOGETHER","今天一起写题吧，互相督促一下！"),
        new Template("CHEER","加油！难题慢慢想，今天也要有所收获。"),
        new Template("REST","写题辛苦啦，记得休息一下再继续！"));

    private void visible(long viewer,long target) {
        auth.requireVisibleProfile(target,viewer,false); // 关注不授予档案访问权，管理员也遵守社交可见性。
    }
    private boolean follows(long from,long to) {
        return Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from user_follow where follower_id=? and following_id=?)",Boolean.class,from,to));
    }
    private Map<String,Object> relationship(long viewer,long target) {
        boolean following=follows(viewer,target), followedBy=follows(target,viewer);
        return Map.of("following",following,"mutual",following&&followedBy);
    }
    public Map<String,Object> state(long viewer,long target) {
        visible(viewer,target);
        return relationship(viewer,target);
    }
    // 相同用户对固定顺序加锁，使发送检查、关注与取消关注不能交错提交。
    private void lockPair(long actor,long target) {
        if(actor==target) throw ApiException.validation("不能关注或发送消息给自己");
        var rows=db.queryForList("select id,status from app_user where id in (?,?) order by id for update",actor,target);
        if(rows.size()!=2 || rows.stream().anyMatch(r->!"ACTIVE".equals(r.get("status")))) throw ApiException.notFound("用户不存在或不可用");
    }
    @Transactional
    public Map<String,Object> follow(long viewer,long target) {
        lockPair(viewer,target); visible(viewer,target);
        db.update("insert into user_follow(follower_id,following_id) values (?,?) on conflict do nothing",viewer,target);
        return relationship(viewer,target);
    }
    @Transactional
    public Map<String,Object> unfollow(long viewer,long target) {
        // 对方后来隐藏档案也允许取消关注。
        db.queryForList("select id from app_user where id in (?,?) order by id for update",viewer,target);
        db.update("delete from user_follow where follower_id=? and following_id=?",viewer,target);
        return relationship(viewer,target);
    }
    public Set<Long> followingIds(long viewer) {
        return new HashSet<>(db.queryForList("select following_id from user_follow where follower_id=?",Long.class,viewer));
    }
    public Map<String,Object> counts(long viewer) {
        return db.queryForMap("""
            select (select count(*) from user_follow f join app_user u on u.id=f.following_id
                    where f.follower_id=? and u.status='ACTIVE') as following,
                   (select count(*) from user_follow f join app_user u on u.id=f.follower_id
                    where f.following_id=? and u.status='ACTIVE') as followers
            """,viewer,viewer);
    }
    public Map<String,Object> connections(long viewer,String type,long before) {
        String predicate=switch(type) {
            case "FOLLOWING" -> "f.outgoing";
            case "FRIENDS" -> "f.outgoing and f.incoming";
            default -> throw ApiException.validation("关注列表类型无效");
        };
        var rows=db.queryForList("""
            select u.id,u.display_name as "displayName",f.outgoing as following,
                   f.outgoing and f.incoming as mutual
            from app_user u join (
              select peer,bool_or(outgoing) outgoing,bool_or(incoming) incoming from (
                select following_id peer,true outgoing,false incoming from user_follow where follower_id=?
                union all select follower_id,false,true from user_follow where following_id=?
              ) edges group by peer
            ) f on f.peer=u.id where u.status='ACTIVE' and u.id<? and
            """+predicate+" order by u.id desc limit 21",viewer,viewer,before<=0?Long.MAX_VALUE:before);
        boolean more=rows.size()>20;
        var page=more?rows.subList(0,20):rows;
        return Map.of("items",page,"hasMore",more,"nextCursor",page.isEmpty()?0:page.getLast().get("id"));
    }
    @Transactional
    public long send(long sender,long recipient,String code,UUID requestId) {
        Template template=TEMPLATES.stream().filter(t->t.code().equals(code)).findFirst().orElseThrow(()->ApiException.validation("请选择提供的固定消息"));
        lockPair(sender,recipient);
        String key="friend:"+sender+":"+requestId;
        var existing=db.queryForList("select id,template_code from study_notification where user_id=? and dedup_key=?",recipient,key);
        if(!existing.isEmpty()) {
            if(!code.equals(existing.getFirst().get("template_code"))) throw ApiException.conflict("同一次发送不能更换内容");
            return ((Number)existing.getFirst().get("id")).longValue();
        }
        visible(sender,recipient);
        if(!follows(sender,recipient)||!follows(recipient,sender)) throw ApiException.forbidden("只有互相关注后才能发送好友消息");
        int daily=db.queryForObject("""
            select count(*) from study_notification where created_by=? and user_id=? and category='FRIEND'
            and created_at >= date_trunc('day',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'
            """,Integer.class,sender,recipient);
        if(daily>=3) throw ApiException.validation("今天已给这位好友发送 3 条消息，明天再来吧");
        int allDaily=db.queryForObject("""
            select count(*) from study_notification where created_by=? and category='FRIEND'
            and created_at >= date_trunc('day',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'
            """,Integer.class,sender);
        if(allDaily>=30) throw ApiException.validation("今天发送的好友消息已达到上限");
        if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from study_notification where created_by=? and user_id=? and category='FRIEND' and created_at>now()-interval '1 minute')",Boolean.class,sender,recipient)))
            throw ApiException.validation("刚刚已经提醒过这位好友，请一分钟后再发");
        // 仅站内投递，不创建邮件任务，不接受客户端正文或发送者身份。
        return db.queryForObject("""
            insert into study_notification(user_id,kind,dedup_key,title,body,created_by,category,template_code)
            values (?,'FRIEND_NUDGE',?,'好友的学习提醒',?,?,'FRIEND',?) returning id
            """,Long.class,recipient,key,template.text(),sender,code);
    }
}
