package com.szpu.algohub.school;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/schools")
public class SchoolController {

    private final SchoolService schoolService;

    public SchoolController(SchoolService schoolService) {
        this.schoolService = schoolService;
    }

    public record JoinRequest(@NotBlank @jakarta.validation.constraints.Size(max = 64) String inviteCode, Integer enrollmentYear,
                              @NotBlank(message="请填写班级") @jakarta.validation.constraints.Size(max = 64) String className,
                              @NotBlank(message="请填写学号") @jakarta.validation.constraints.Size(max = 32) String studentNo) {
    }

    public record ProfileRequest(Integer enrollmentYear,
                                 @NotBlank(message="请填写班级") @jakarta.validation.constraints.Size(max = 64) String className,
                                 @NotBlank(message="请填写学号") @jakarta.validation.constraints.Size(max = 32) String studentNo) {
    }

    /** inviteCode 选填：填对直接加入，不填进待审核 */
    public record SelectionRequest(@jakarta.validation.constraints.NotNull Long schoolId,
                                   @jakarta.validation.constraints.Size(max = 64) String inviteCode,
                                   @jakarta.validation.constraints.NotNull(message="请选择入学年级") Integer enrollmentYear,
                                   @NotBlank @jakarta.validation.constraints.Size(max=64) String className,
                                   @NotBlank @jakarta.validation.constraints.Size(max=32) String studentNo) {}

    /** 选学校用的列表：只有名称和简称，不带邀请码 */
    public record SchoolOption(Long id, String name, String shortName) {
        static SchoolOption of(School school) {
            return new SchoolOption(school.getId(), school.getName(), school.getShortName());
        }
    }

    @PostMapping("/select")
    public ApiResult<SchoolMember> select(@Valid @RequestBody SelectionRequest request) {
        return ApiResult.ok(schoolService.selectSchoolWithProfile(CurrentUser.requireId(), request.schoolId(), request.inviteCode(), request.enrollmentYear(), request.className(), request.studentNo()));
    }

    @GetMapping
    public ApiResult<List<SchoolOption>> list() {
        return ApiResult.ok(schoolService.listSchools().stream().map(SchoolOption::of).toList());
    }

    @GetMapping("/memberships")
    public ApiResult<List<SchoolMember>> myMemberships() {
        return ApiResult.ok(schoolService.myMemberships(CurrentUser.requireId()));
    }

    @PostMapping("/join")
    public ApiResult<SchoolMember> join(@Valid @RequestBody JoinRequest request) {
        return ApiResult.ok(schoolService.applyByInviteCode(CurrentUser.requireId(), request.inviteCode(),
                request.enrollmentYear(), request.className(), request.studentNo()));
    }

    @PostMapping("/memberships/{id}")
    public ApiResult<SchoolMember> updateProfile(@PathVariable Long id, @Valid @RequestBody ProfileRequest request) {
        return ApiResult.ok(schoolService.updateProfile(CurrentUser.requireId(), id,
                request.enrollmentYear(), request.className(), request.studentNo()));
    }
}
