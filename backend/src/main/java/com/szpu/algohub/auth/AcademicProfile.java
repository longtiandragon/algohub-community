package com.szpu.algohub.auth;

import java.util.Comparator;
import java.util.List;
import com.szpu.algohub.school.SchoolMember;

/** Existing school data takes precedence; missing fields fall back to personal data. */
public record AcademicProfile(Integer enrollmentYear, String className, String studentNo) {
    public static SchoolMember membership(List<SchoolMember> memberships) {
        return memberships.stream().filter(m -> List.of("APPROVED", "PENDING").contains(m.getStatus()))
                .min(Comparator.comparingInt((SchoolMember m) -> "APPROVED".equals(m.getStatus()) ? 0 : 1)
                        .thenComparing(SchoolMember::getId)).orElse(null);
    }
    public static AcademicProfile of(AppUser user, List<SchoolMember> memberships) {
        SchoolMember member = membership(memberships);
        return new AcademicProfile(member != null && member.getEnrollmentYear() != null ? member.getEnrollmentYear() : user.getEnrollmentYear(),
                fallback(member == null ? null : member.getClassName(), user.getClassName()),
                fallback(member == null ? null : member.getStudentNo(), user.getStudentNo()));
    }
    private static String fallback(String value, String other) { return present(value) ? value : other; }
    public static boolean present(String value) { return value != null && !value.isBlank(); }
    public boolean complete() { return enrollmentYear != null && present(className) && present(studentNo); }
}
