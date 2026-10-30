package com.szpu.algohub.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.school.SchoolService;
import com.szpu.algohub.school.EnrollmentYearService;

@Service
public class OnboardingService {
    private final AppUserMapper users;
    private final SchoolService schools;
    private final EnrollmentYearService years;
    public OnboardingService(AppUserMapper users, SchoolService schools, EnrollmentYearService years) {
        this.users=users; this.schools=schools; this.years=years;
    }
    private AppUser verified(Long id) {
        AppUser user=users.lockIdentity(id);
        if(user==null) throw ApiException.notFound("用户不存在");
        if("ADMIN".equals(user.getRole())) throw ApiException.forbidden("管理员无需完成学生注册引导");
        if(users.verifiedEmailCount(id)==0) throw ApiException.forbidden("请先验证登录邮箱");
        return user;
    }
    @Transactional
    public void complete(Long id, AuthDtos.OnboardingRequest request) {
        AppUser user=verified(id);
        if(clean(request.className())==null || clean(request.studentNo())==null) throw ApiException.validation("请填写班级和学号");
        // Repeat requests cannot rejoin a school or consume an invitation twice.
        if(user.isOnboardingCompleted()) return;
        if(request.displayName()==null || request.displayName().isBlank() || request.enrollmentYear()==null)
            throw ApiException.validation("请填写昵称并选择入学年级");
        years.validate(request.enrollmentYear(),user.getEnrollmentYear());
        if(request.schoolId()!=null) {
            var member=schools.selectSchool(id,request.schoolId(),request.inviteCode());
            schools.updateProfile(id,member.getId(),request.enrollmentYear(),request.className(),request.studentNo());
        } else if(request.inviteCode()!=null && !request.inviteCode().isBlank()) {
            throw ApiException.validation("请先选择邀请码对应的学校");
        }
        user.setNicknameNeedsReview(false);
        user.setDisplayName(request.displayName().trim());
        user.setEnrollmentYear(request.enrollmentYear());
        user.setClassName(clean(request.className())); user.setStudentNo(clean(request.studentNo()));
        user.setOnboardingCompleted(true);
        users.updateOnboarding(user);
    }
    @Transactional
    public void updateAcademic(Long id, AuthDtos.AcademicRequest request) {
        AppUser user=verified(id);
        if(clean(request.className())==null || clean(request.studentNo())==null) throw ApiException.validation("请填写班级和学号");
        if(request.enrollmentYear()==null) throw ApiException.validation("请选择入学年级");
        years.validate(request.enrollmentYear(),user.getEnrollmentYear());
        user.setEnrollmentYear(request.enrollmentYear());
        user.setClassName(clean(request.className())); user.setStudentNo(clean(request.studentNo()));
        users.updateOnboarding(user);
    }
    /** Repair legacy profiles without selecting a school, changing existing fields, or rebinding accounts. */
    @Transactional
    public void completeAcademic(Long id, AuthDtos.AcademicRequest request) {
        AppUser user = verified(id);
        if (!user.isOnboardingCompleted()) throw ApiException.validation("请先完成注册引导");
        var memberships = schools.myMemberships(id);
        var existing = AcademicProfile.of(user, memberships);
        Integer year = existing.enrollmentYear() != null ? existing.enrollmentYear() : request.enrollmentYear();
        String className = AcademicProfile.present(existing.className()) ? existing.className() : clean(request.className());
        String studentNo = AcademicProfile.present(existing.studentNo()) ? existing.studentNo() : clean(request.studentNo());
        if (year == null || className == null || studentNo == null) throw ApiException.validation("请补齐年级、班级和学号");
        years.validate(year, existing.enrollmentYear());
        var member = AcademicProfile.membership(memberships);
        if (member != null) schools.updateProfile(id, member.getId(), year, className, studentNo);
        user.setEnrollmentYear(year);
        user.setClassName(className);
        user.setStudentNo(studentNo);
        users.updateOnboarding(user);
    }
    private static String clean(String s) { return s==null || s.isBlank() ? null : s.trim(); }
}
