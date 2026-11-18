package com.szpu.algohub.weekly;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class WeeklyReportTest {
    @Autowired JdbcTemplate db;
    @Autowired WeeklyReport report;
    @Autowired ObjectMapper json;

    private long school, other, actor, assignment;

    @BeforeEach
    void setup() {
        school = school();
        other = school();
        actor = member(school, 2026);
        assignment = training("CODEFORCES");
    }

    @Test
    void scoresAreKeptSeparateFromCumulativeGoalsAndPrivacyIsSchoolScoped() throws Exception {
        long student = member(school, 2026);
        long peer = member(school, 2026);
        long hidden = member(school, 2026);
        long unbound = member(school, 2026);
        long wrongYear = member(school, 2025);
        long outsider = member(other, 2026);
        db.update("insert into user_privacy(user_id,profile_visibility) values (?,'PRIVATE')",hidden);
        long first = account(student,"CODEFORCES"), second = account(student,"CODEFORCES");
        String ranked = """
            {"kind":"VIRTUAL","cells":{"A":{"solved":true,"wrong":0,"seconds":15}},
             "score":1.0,"penalty":15}
            """;
        String practice = """
            {"kind":"PRACTICE","cells":{"A":{"solved":true,"wrong":0},"B":{"solved":true,"wrong":2}},
             "score":null,"penalty":0}
            """;
        save(assignment,first,"[" + ranked + "," + practice + "]");
        save(assignment,second,"[" + practice + "]");
        save(assignment,account(peer,"CODEFORCES"),"[" + ranked.replace("VIRTUAL","OFFICIAL") + "]");
        save(assignment,account(hidden,"CODEFORCES"),"[" + practice + "]");
        save(assignment,account(wrongYear,"CODEFORCES"),"[" + ranked + "]");
        save(assignment,account(outsider,"CODEFORCES"),"[" + ranked + "]");

        JsonNode result = json.valueToTree(report.detail(school,assignment,actor,true));
        assertThat(find(result,"students",student).get("solved").asInt()).isEqualTo(2);
        assertThat(find(result,"students",student).get("status").asText()).isEqualTo("MET");
        assertThat(find(result,"students",actor).get("status").asText()).isEqualTo("UNBOUND");
        assertThat(find(result,"students",hidden).get("status").asText()).isEqualTo("MET");
        assertThat(find(result,"students",outsider)).isNull();
        assertThat(find(result,"students",wrongYear)).isNull();
        assertThat(result.get("rows").size()).isEqualTo(3);
        assertThat(result.at("/rows/0/rank").asInt()).isOne();
        assertThat(result.at("/rows/1/rank").asInt()).isOne();
        assertThat(result.at("/rows/2/rank").isNull()).isTrue();
        assertThat(result.at("/rows/2/cells/B/wrong").asInt()).isEqualTo(2);
        assertThat(result.at("/problemStats/A/solved").asInt()).isEqualTo(2);
        assertThat(result.at("/problemStats/B/tried").asInt()).isOne();
        assertThat(find(result,"rows",hidden)).isNull();

        JsonNode studentView = json.valueToTree(report.list(school,actor,false).getFirst());
        assertThat(find(studentView,"students",hidden)).isNull();
        assertThat(find(studentView,"students",unbound).get("status").asText()).isEqualTo("UNBOUND");
        assertThat(studentView.has("rows")).isFalse();
        JsonNode selfView = json.valueToTree(report.detail(school,assignment,hidden,false));
        assertThat(find(selfView,"students",hidden)).isNotNull();
        assertThat(find(selfView,"rows",hidden)).isNull();
        assertThatThrownBy(() -> report.detail(other,assignment,outsider,false)).isInstanceOf(ApiException.class);
    }

    @Test
    void atcoderUsesExistingSolvedKeysWithoutInventingVpResults() {
        long contest = training("ATCODER");
        long account = account(actor,"ATCODER");
        for (String key : List.of("weekly_test_a","weekly_test_b")) {
            long problem = db.queryForObject("""
                insert into problem(platform,problem_key,title) values ('ATCODER',?,?) returning id
                """,Long.class,key,key);
            db.update("insert into user_solved_problem(user_id,account_id,problem_id,platform) values (?,?,?,'ATCODER')",
                    actor,account,problem);
        }
        JsonNode result = json.valueToTree(report.detail(school,contest,actor,false));
        assertThat(find(result,"students",actor).get("solved").asInt()).isEqualTo(2);
        assertThat(find(result,"students",actor).get("status").asText()).isEqualTo("MET");
        assertThat(result.at("/problemStats/A/solved").asInt()).isOne();
        assertThat(result.get("rows").size()).isZero();
    }

    @Test
    void zeroPendingMissingAndFailedSnapshotsRemainDistinguishable() {
        long a = account(actor,"CODEFORCES");
        assertThat(actorStatus()).isEqualTo("SYNCING");
        save(assignment,a,"[]");
        assertThat(actorStatus()).isEqualTo("PENDING");
        db.update("update weekly_account_result set failed=true where training_id=? and account_id=?",assignment,a);
        assertThat(actorStatus()).isEqualTo("ERROR");
    }

    private String actorStatus() {
        JsonNode result = json.valueToTree(report.detail(school,assignment,actor,true));
        return find(result,"students",actor).get("status").asText();
    }

    private JsonNode find(JsonNode result, String table, long id) {
        for (JsonNode row : result.get(table)) if (row.get("userId").asLong() == id) return row;
        return null;
    }

    private long school() {
        return db.queryForObject("insert into school(name) values (?) returning id",Long.class,"Weekly "+UUID.randomUUID());
    }

    private long member(long schoolId, int year) {
        String name = UUID.randomUUID().toString();
        long user = db.queryForObject("insert into app_user(username,display_name) values (?,?) returning id",Long.class,name,name);
        db.update("insert into school_member(school_id,user_id,enrollment_year,status) values (?,?,?,'APPROVED')",schoolId,user,year);
        return user;
    }

    private long account(long user,String platform) {
        String handle = UUID.randomUUID().toString();
        return db.queryForObject("""
            insert into platform_account(user_id,platform,handle,platform_uid,ownership_status) values (?,?,?,?,'VERIFIED') returning id
            """,Long.class,user,platform,handle,handle);
    }

    private long training(String platform) {
        String meta = """
            {"platform":"%s","ref":"1","title":"Round","category":"test","url":"https://example.org",
             "tasks":[{"index":"A","key":"weekly_test_a"},{"index":"B","key":"weekly_test_b"}]}
            """.formatted(platform);
        return db.queryForObject("""
            insert into weekly_training(school_id,created_by,week_start,platform,contest_ref,metadata,targets)
            values (?,?,current_date,?,'1',?::jsonb,'{"2026":2}') returning id
            """, Long.class,school,actor,platform,meta);
    }

    private void save(long contest, long account, String result) {
        db.update("""
            insert into weekly_account_result(training_id,account_id,result,refreshed_at) values (?,?,?::jsonb,now())
            """,contest,account,result);
    }
}
