package com.szpu.algohub.school;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.common.ApiException;

@Service
public class SchoolService {

    private final SchoolMapper schoolMapper;
    private final SchoolMemberMapper memberMapper;
    private final EnrollmentYearService years;

    public SchoolService(SchoolMapper schoolMapper, SchoolMemberMapper memberMapper, EnrollmentYearService years) {
        this.schoolMapper = schoolMapper;
        this.memberMapper = memberMapper;
        this.years = years;
    }

    public List<School> listSchools() {
        return schoolMapper.findAll();
    }

    public School findSchool(Long schoolId) {
        return schoolId == null ? null : schoolMapper.findById(schoolId);
    }

    /**
     * 选学校（2026-09-29 定）：所有申请进入待审核；邀请码只校验目标学校，由管理员核验后通过。
     * 被管理员拒绝过的不能自己再选回来，填码也不行，只能等管理员改成通过。保持已有归属不被重复请求覆盖。
     */
    @Transactional
    public SchoolMember selectSchool(Long userId, Long schoolId, String inviteCode) {
        memberMapper.lockUser(userId);
        School school = schoolId == null ? null : schoolMapper.findById(schoolId);
        if (school == null) {
            throw ApiException.validation("请选择有效的学校");
        }
        boolean codeGiven = inviteCode != null && !inviteCode.isBlank();
        boolean codeOk = codeGiven && school.getInviteCode() != null && school.getInviteCode().equalsIgnoreCase(inviteCode.trim());
        if (codeGiven && !codeOk) {
            throw ApiException.validation("学校邀请码不对，请核对后再填；没有邀请码可以留空，提交后等管理员审核");
        }
        var memberships = memberMapper.findByUser(userId);
        for (var member : memberships) {
            if ("APPROVED".equals(member.getStatus())) {
                if (member.getSchoolId().equals(schoolId)) return member;
                throw ApiException.conflict("已经选择学校，请勿重复加入其他学校");
            }
        }
        for (var member : memberships) {
            if (!member.getSchoolId().equals(schoolId)) {
                continue;
            }
            if ("REJECTED".equals(member.getStatus())) {
                throw ApiException.forbidden("你加入这所学校的申请没有通过，如有疑问请联系管理员");
            }
            if (codeOk) {
                memberMapper.review(member.getId(), "APPROVED", null, null);
            }
            return memberMapper.findById(member.getId());
        }
        SchoolMember member = new SchoolMember();
        member.setSchoolId(schoolId);
        member.setUserId(userId);
        member.setMemberRole("MEMBER");
        member.setStatus(codeOk ? "APPROVED" : "PENDING");
        memberMapper.insert(member);
        return memberMapper.findById(member.getId());
    }

    @Transactional
    public SchoolMember selectSchoolWithProfile(Long userId, Long schoolId, String inviteCode, Integer enrollmentYear, String className, String studentNo) {
        if (enrollmentYear == null) throw ApiException.validation("请选择入学年级");
        SchoolMember member = selectSchool(userId, schoolId, inviteCode);
        return updateProfile(userId, member.getId(), enrollmentYear, className, studentNo);
    }

    public School defaultSchool() {
        List<School> all = schoolMapper.findAll();
        if (all.isEmpty()) {
            throw ApiException.notFound("尚未初始化学校数据");
        }
        return all.get(0);
    }

    /**
     * 邀请码入口：码对上哪所学校就直接加入哪所。学号等身份信息仅用于成员管理。
     */
    @Transactional
    public SchoolMember applyByInviteCode(Long userId, String inviteCode, Integer enrollmentYear,
                                          String className, String studentNo) {
        if (enrollmentYear == null) throw ApiException.validation("请选择入学年级");
        School school = schoolMapper.findByInviteCode(inviteCode == null ? "" : inviteCode.trim());
        if (school == null) {
            throw ApiException.validation("学校邀请码无效");
        }
        SchoolMember member = selectSchool(userId, school.getId(), inviteCode);
        years.validate(enrollmentYear, member.getEnrollmentYear());
        member.setEnrollmentYear(enrollmentYear);
        member.setClassName(className);
        member.setStudentNo(studentNo);
        memberMapper.updateProfile(member);
        return memberMapper.findById(member.getId());
    }

    public List<SchoolMember> myMemberships(Long userId) {
        return memberMapper.findByUser(userId);
    }

    public boolean isApprovedMember(Long userId) {
        return memberMapper.countApproved(userId) > 0;
    }

    /**
     * 用户已通过的学校（每人至多一个）。校内榜单、动态与"仅校内可见"都以它为准：
     * 学校身份只看服务端的成员记录，不接受前端传来的学校编号。没有学校时为 null。
     */
    public Long approvedSchoolId(Long userId) {
        return userId == null ? null : memberMapper.approvedSchoolId(userId);
    }

    /** 两人是否是同一所学校已通过的成员。 */
    public boolean sameSchool(Long userId, Long otherUserId) {
        Long mine = approvedSchoolId(userId);
        return mine != null && mine.equals(approvedSchoolId(otherUserId));
    }

    public List<SchoolMember> listMembers(Long schoolId, String status) {
        return memberMapper.findBySchool(schoolId, status);
    }

    @Transactional
    public SchoolMember review(Long memberId, boolean approve, Long reviewerId, String reason) {
        SchoolMember member = memberMapper.findById(memberId);
        if (member == null) {
            throw ApiException.notFound("成员申请不存在");
        }
        memberMapper.lockUser(member.getUserId());
        Long current = approvedSchoolId(member.getUserId());
        if (approve && current != null && !current.equals(member.getSchoolId())) {
            throw ApiException.conflict("该同学已是其他学校的成员，每人只能属于一所学校");
        }
        memberMapper.review(memberId, approve ? "APPROVED" : "REJECTED", reviewerId, approve ? null : reason);
        return memberMapper.findById(memberId);
    }

    @Transactional
    public SchoolMember updateProfile(Long userId, Long memberId, Integer enrollmentYear,
                                      String className, String studentNo) {
        if (enrollmentYear == null) throw ApiException.validation("请选择入学年级");
        memberMapper.lockUser(userId);
        SchoolMember member = memberMapper.findById(memberId);
        if (member == null || !member.getUserId().equals(userId)) {
            throw ApiException.notFound("成员信息不存在");
        }
        years.validate(enrollmentYear, member.getEnrollmentYear());
        member.setEnrollmentYear(enrollmentYear);
        member.setClassName(className);
        member.setStudentNo(studentNo);
        memberMapper.updateProfile(member);
        return memberMapper.findById(memberId);
    }
    /** 管理员按学校名单改成员的年级、班级、学号（同学填错时），记一条审计。 */
    @Transactional
    public SchoolMember adminUpdateProfile(Long memberId, Integer enrollmentYear, String className,
                                           String studentNo, Long actor) {
        SchoolMember member = memberMapper.findById(memberId);
        if (member == null) {
            throw ApiException.notFound("成员不存在");
        }
        memberMapper.lockUser(member.getUserId());
        member = memberMapper.findById(memberId);
        years.validate(enrollmentYear, member.getEnrollmentYear());
        member.setEnrollmentYear(enrollmentYear);
        member.setClassName(blankToNull(className));
        member.setStudentNo(blankToNull(studentNo));
        memberMapper.updateProfile(member);
        memberMapper.auditProfile(actor, memberId);
        return memberMapper.findById(memberId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
