package com.szpu.algohub.weekly;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.weekly.WeeklySource.Cell;
import com.szpu.algohub.weekly.WeeklySource.Meta;
import com.szpu.algohub.weekly.WeeklySource.Row;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A student's cumulative goal and their timed performance are separate measures. */
@Service
@Transactional(readOnly = true)
public class WeeklyReport {
    private final NamedParameterJdbcTemplate db;
    private final ObjectMapper json;
    private static final TypeReference<Meta> META = new TypeReference<>() {};
    private static final TypeReference<Map<Integer,Integer>> TARGETS = new TypeReference<>() {};
    private static final TypeReference<List<Row>> ROWS = new TypeReference<>() {};

    public WeeklyReport(NamedParameterJdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public List<Map<String,Object>> list(long school, long actor, boolean manager) {
        return load(school,null,null,actor,manager).stream().map(t->t.output(false,manager)).toList();
    }

    public Map<String,Object> detail(long school, long id, long actor, boolean manager) {
        var matches = load(school,id,null,actor,manager);
        if (matches.isEmpty()) throw ApiException.notFound("本校训练赛不存在");
        return matches.getFirst().output(true,manager);
    }

    public Map<String,Object> overview(long school,java.time.LocalDate day,long actor,boolean manager) {
        return overview(school, day, actor, manager, false);
    }

    public Map<String,Object> overview(long school,java.time.LocalDate day,long actor,boolean manager, boolean allTime) {
        var monday=day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        var trainings=load(school,null,allTime ? null : monday,actor,manager);
        record Entry(Student student,Set<String> tasks) {}
        Map<Long,Entry> totals=new LinkedHashMap<>();
        for(var t:trainings) {
            if(!"ALL".equals(t.audience) || !"PUBLISHED".equals(t.status)) continue;
            for(var student:t.students.values()) {
                if(student.boardSolved.isEmpty()) continue;
                var entry=totals.computeIfAbsent(student.id,unused->new Entry(student,new HashSet<>()));
                for(var task:t.meta.tasks()) if(student.boardSolved.contains(task.index()))
                    entry.tasks.add(t.meta.platform()+":"+task.key());
            }
        }
        var ordered=totals.values().stream().sorted(Comparator.<Entry>comparingInt(e->e.tasks.size())
                .reversed().thenComparingLong(e->e.student.id)).toList();
        List<Map<String,Object>> ranking=new ArrayList<>();
        int previous=-1,rank=0;
        for(var entry:ordered) {
            int solved=entry.tasks.size();
            if(previous!=solved) rank=ranking.size()+1;
            previous=solved;
            ranking.add(Map.of("userId",entry.student.id,"name",entry.student.name,"realName",entry.student.realName,"year",entry.student.year,"rank",rank,"solved",solved));
        }
        return Map.of("weekStart",monday,"ranking",ranking,"contests",trainings.stream().filter(t -> monday.toString().equals(t.weekStart)).map(t->t.output(false,manager)).toList());
    }

    private List<Training> load(long school,Long id,java.time.LocalDate week,long actor,boolean manager) {
        Map<String,Object> params = new HashMap<>(Map.of("school",school,"actor",actor,"manager",manager));
        String filter = "";
        if (id != null) {
            params.put("id", id);
            filter = " and id = :id";
        }
        if(week!=null) {params.put("week",week);filter+=" and week_start=:week";}
        Map<Long,Training> trainings = new LinkedHashMap<>();
        db.query("""
            select id,week_start,metadata::text,targets::text,audience,effective_status,publish_at,student_ids from weekly_effective_training
            where school_id=:school and (:manager or (effective_status='PUBLISHED' and (audience='ALL' or :actor=any(student_ids))))
            """
                + filter + " order by week_start desc,id desc", params, (RowCallbackHandler) rs -> {
            long trainingId = rs.getLong("id");
            trainings.put(trainingId, new Training(trainingId, rs.getString("week_start"),
                    read(rs.getString("metadata"), META), read(rs.getString("targets"), TARGETS),rs));
        });
        if (trainings.isEmpty()) return List.of();
        params.put("ids", trainings.keySet());
        db.query("""
            select t.id,u.id as student,u.display_name,coalesce(m.real_name,'') as real_name,m.enrollment_year,
                   a.id as account,b.account_id is not null as visible,
                   a.last_success_at,a.history_complete,a.sync_status,
                   r.result::text,r.failed,
                   array(
                     select item->>'index' from jsonb_array_elements(t.metadata->'tasks') item
                     join problem p on p.platform=t.platform and p.problem_key=item->>'key'
                     join user_solved_problem s on s.problem_id=p.id and s.account_id=a.id
                   ) as solved
            from weekly_training t
            join school_member m on m.school_id=t.school_id and m.status='APPROVED'
              and jsonb_exists(t.targets,m.enrollment_year::text) and (t.audience='ALL' or m.user_id=any(t.student_ids))
            join app_user u on u.id=m.user_id and u.status='ACTIVE' and u.role='STUDENT'
            left join user_privacy privacy on privacy.user_id=u.id
            left join platform_account a on a.user_id=u.id and a.platform=t.platform
              and a.ownership_status='VERIFIED'
            left join school_board_account b on b.account_id=a.id and b.school_id=t.school_id
            left join weekly_account_result r on r.training_id=t.id and r.account_id=a.id
            where t.school_id=:school and t.id in (:ids)
              and (:manager or u.id=:actor or b.account_id is not null
                   or (a.id is null and coalesce(privacy.show_on_ranking,true)
                       and coalesce(privacy.profile_visibility,'SCHOOL') <> 'PRIVATE'))
            order by m.enrollment_year desc,u.display_name,u.id,a.id
            """, params, (RowCallbackHandler) rs -> {
            Training training = trainings.get(rs.getLong("id"));
            String rows = rs.getString("result");
            training.accept(rs, rows == null ? List.of() : read(rows, ROWS), rows != null, id != null);
        });
        return List.copyOf(trainings.values());
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取训练赛数据", e);
        }
    }

    private static class Training {
        final long id;
        final String weekStart;
        final Meta meta;
        final Map<Integer,Integer> targets;
        final Map<Long,Student> students = new LinkedHashMap<>();
        boolean standingsRefreshFailed;

        final String audience,status;
        final java.time.OffsetDateTime publishAt;
        final Long[] studentIds;
        Training(long id,String weekStart,Meta meta,Map<Integer,Integer> targets,ResultSet rs) throws SQLException {
            audience=rs.getString("audience");
            status=rs.getString("effective_status");
            publishAt=rs.getObject("publish_at",java.time.OffsetDateTime.class);
            studentIds=(Long[])rs.getArray("student_ids").getArray();
            this.id = id;
            this.weekStart = weekStart;
            this.meta = meta;
            this.targets = targets;
        }

        void accept(ResultSet rs, List<Row> rows, boolean refreshed, boolean detail) throws SQLException {
            long user = rs.getLong("student");
            Student s = students.get(user);
            if (s == null) {
                s = new Student(user, rs.getString("display_name"), rs.getString("real_name"), rs.getInt("enrollment_year"));
                students.put(user, s);
            }
            if (rs.getObject("account") == null) return;
            s.bound = true;
            standingsRefreshFailed |= rs.getBoolean("failed");
            boolean atcoder = "ATCODER".equals(meta.platform());
            if (atcoder) {
                s.waiting |= rs.getTimestamp("last_success_at") == null || !rs.getBoolean("history_complete");
                String status = rs.getString("sync_status");
                s.failed |= "FAILED".equals(status) || "ERROR".equals(status);
            } else {
                s.waiting |= !refreshed;
                s.failed |= rs.getBoolean("failed");
            }
            String[] known = (String[]) rs.getArray("solved").getArray();
            s.solved.addAll(Arrays.asList(known));
            boolean boardVisible = rs.getBoolean("visible");
            if (boardVisible) {
                s.boardSolved.addAll(Arrays.asList(known));
                s.boardTried.addAll(Arrays.asList(known));
            }
            for (Row row : rows) {
                for (var entry : row.cells().entrySet()) {
                    if (entry.getValue().solved()) s.solved.add(entry.getKey());
                    if (boardVisible) {
                        s.boardTried.add(entry.getKey());
                        if (entry.getValue().solved()) s.boardSolved.add(entry.getKey());
                    }
                }
                if (detail && boardVisible) s.addBoardRow(row);
            }
        }

        Map<String,Object> output(boolean detail,boolean manager) {
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("id",id);
            out.put("weekStart",weekStart);
            out.put("meta",meta);
            out.put("targets",targets);
            out.put("audience",audience);
            out.put("status",status);
            out.put("publishAt",publishAt);
            if(detail && manager) out.put("studentIds",studentIds);
            out.put("students",!detail && audience.equals("SELECTED") ? List.of() : students.values().stream().map(s -> s.progress(targets.get(s.year))).toList());
            if (detail) {
                List<Standing> timed = new ArrayList<>();
                List<Standing> practice = new ArrayList<>();
                Map<String,Map<String,Integer>> stats = new LinkedHashMap<>();
                for (var task : meta.tasks()) {
                    String index = task.index();
                    int solved = 0, tried = 0;
                    for (var student : students.values()) {
                        if (student.boardSolved.contains(index)) solved++;
                        if (student.boardTried.contains(index)) tried++;
                    }
                    stats.put(index, Map.of("solved",solved,"tried",tried));
                }
                for (Student student : students.values()) {
                    if (student.best != null) timed.add(new Standing(student,student.best));
                    if (student.practice != null) practice.add(new Standing(student,student.practice));
                }
                timed.sort(Comparator.comparing(Standing::row, PERFORMANCE));
                practice.sort(Comparator.comparingLong((Standing s) -> solvedCount(s.row())).reversed());
                List<Map<String,Object>> rows = new ArrayList<>();
                int rank = 0;
                Row previous = null;
                for (Standing s : timed) {
                    if (previous == null || PERFORMANCE.compare(previous,s.row()) != 0) rank = rows.size()+1;
                    rows.add(s.output(rank));
                    previous = s.row();
                }
                practice.forEach(s -> rows.add(s.output(null)));
                out.put("standingsRefreshFailed",standingsRefreshFailed);
                out.put("rows",rows);
                out.put("problemStats",stats);
            }
            return out;
        }
    }

    private static long solvedCount(Row row) {
        return row.cells().values().stream().filter(Cell::solved).count();
    }

    private static final Comparator<Row> PERFORMANCE = Comparator
            .comparingLong(WeeklyReport::solvedCount).reversed()
            .thenComparing(Row::score, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparingLong(Row::penalty);

    private record Standing(Student student, Row row) {
        Map<String,Object> output(Integer rank) {
            Map<String,Object> out = new LinkedHashMap<>(Map.of("userId",student.id,"name",student.name,"realName",student.realName,
                    "kind",row.kind(),"penalty",row.penalty(),"cells",row.cells()));
            out.put("score",row.score());
            out.put("rank",rank);
            out.put("sourceRank",row.sourceRank());
            return out;
        }
    }

    private static class Student {
        final long id;
        final String name, realName;
        final int year;
        final Set<String> solved = new HashSet<>(), boardSolved = new HashSet<>(), boardTried = new HashSet<>();
        boolean bound, waiting, failed;
        Row best, practice;

        Student(long id, String name, String realName, int year) {
            this.id = id;
            this.name = name;
            this.realName = realName;
            this.year = year;
        }

        Map<String,Object> progress(int goal) {
            String status = "PENDING";
            if (solved.size() >= goal) status = "MET";
            else if (!bound) status = "UNBOUND";
            else if (failed) status = "ERROR";
            else if (waiting) status = "SYNCING";
            return Map.of("userId",id,"name",name,"realName",realName,"year",year,"solved",solved.size(),"goal",goal,"status",status);
        }

        void addBoardRow(Row row) {
            if (!"PRACTICE".equals(row.kind())) {
                if (best == null || PERFORMANCE.compare(row,best) < 0) best = row;
                return;
            }
            if (practice == null) {
                practice = row;
                return;
            }
            // Multiple verified handles may practice the same task. Show its best observed cell once.
            Map<String,Cell> cells = new LinkedHashMap<>(practice.cells());
            row.cells().forEach((index,cell) -> cells.merge(index,cell,Student::bestCell));
            Double score = null;
            if (cells.values().stream().allMatch(c -> c.score() != null)) {
                score = cells.values().stream().mapToDouble(Cell::score).sum();
            }
            practice = new Row("PRACTICE",cells,score,0);
        }

        private static Cell bestCell(Cell a, Cell b) {
            if (a.solved() != b.solved()) return a.solved() ? a : b;
            int byScore = Comparator.nullsFirst(Double::compareTo).compare(a.score(),b.score());
            if (byScore != 0) return byScore > 0 ? a : b;
            return Comparator.nullsLast(Integer::compareTo).compare(a.wrong(), b.wrong()) <= 0 ? a : b;
        }
    }
}
