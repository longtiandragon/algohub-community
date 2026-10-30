package com.szpu.algohub.school;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.szpu.algohub.common.ApiException;

/** 选学校：邀请码对上直接加入，不填码进待审核，被拒绝的不能自己回来（2026-09-29 定）。 */
class SchoolSelectionTest {
    private final SchoolMapper schools = mock(SchoolMapper.class);
    private final SchoolMemberMapper members = mock(SchoolMemberMapper.class);
    private final SchoolService service = new SchoolService(schools, members, mock(EnrollmentYearService.class));

    private School school(String code) {
        School s = new School();
        s.setId(1L);
        s.setInviteCode(code);
        when(schools.findById(1L)).thenReturn(s);
        return s;
    }

    private SchoolMember member(long id, String status) {
        var m = new SchoolMember(); m.setId(id); m.setSchoolId(1L); m.setUserId(2L); m.setStatus(status);
        return m;
    }

    @Test void invalidSchoolIsRejected() {
        assertThatThrownBy(() -> service.selectSchool(2L, 99L, null)).isInstanceOf(ApiException.class);
        verify(members, never()).insert(any());
    }

    @Test void withoutCodeTheNewMemberWaitsForReview() {
        school("szpu-2026");
        service.selectSchool(2L, 1L, null);
        verify(members).insert(argThat(m -> "PENDING".equals(m.getStatus())));
        verify(members, never()).review(anyLong(), anyString(), any(), any());
    }

    @Test void theRightCodeJoinsDirectlyAndAWrongCodeIsRefused() {
        school("szpu-2026");
        service.selectSchool(2L, 1L, " szpu-2026 ");
        verify(members).insert(argThat(m -> "APPROVED".equals(m.getStatus())));
        assertThatThrownBy(() -> service.selectSchool(2L, 1L, "guess")).isInstanceOf(ApiException.class)
                .hasMessageContaining("邀请码不对");
    }

    @Test void aPendingRequestIsApprovedOnlyWithTheCode() {
        school("szpu-2026");
        when(members.findByUser(2L)).thenReturn(List.of(member(3L, "PENDING")));
        service.selectSchool(2L, 1L, null);
        verify(members, never()).review(anyLong(), anyString(), any(), any());
        service.selectSchool(2L, 1L, "szpu-2026");
        verify(members).review(3L, "APPROVED", null, null);
        verify(members, never()).insert(any());
    }

    @Test void aRejectedMemberCannotComeBackEvenWithTheCode() {
        school("szpu-2026");
        when(members.findByUser(2L)).thenReturn(List.of(member(3L, "REJECTED")));
        assertThatThrownBy(() -> service.selectSchool(2L, 1L, null)).isInstanceOf(ApiException.class).hasMessageContaining("没有通过");
        assertThatThrownBy(() -> service.selectSchool(2L, 1L, "szpu-2026")).isInstanceOf(ApiException.class);
        verify(members, never()).review(anyLong(), anyString(), any(), any());
        verify(members, never()).insert(any());
    }

    @Test void repeatSelectionIsIdempotentAndAnotherSchoolIsRejected() {
        when(schools.findById(anyLong())).thenReturn(new School());
        var member = member(3L, "APPROVED");
        when(members.findByUser(2L)).thenReturn(List.of(member));
        assertThat(service.selectSchool(2L, 1L, null)).isSameAs(member);
        assertThatThrownBy(() -> service.selectSchool(2L, 2L, null)).isInstanceOf(ApiException.class);
        verify(members, never()).insert(any());
    }
}
