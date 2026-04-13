package com.xddcodec.fs.system.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.xddcodec.fs.framework.common.exception.BusinessException;
import com.xddcodec.fs.framework.common.utils.I18nUtils;
import com.xddcodec.fs.system.domain.SysWorkspace;
import com.xddcodec.fs.system.domain.SysWorkspaceInvitation;
import com.xddcodec.fs.system.domain.SysWorkspaceMember;
import com.xddcodec.fs.system.domain.dto.CreateInvitationCmd;
import com.xddcodec.fs.system.domain.dto.CreateWorkspaceMemberCmd;
import com.xddcodec.fs.system.domain.vo.WorkspaceInvitationVO;
import com.xddcodec.fs.system.mapper.SysWorkspaceInvitationMapper;
import com.xddcodec.fs.system.service.SysWorkspaceInvitationService;
import com.xddcodec.fs.system.service.SysWorkspaceMemberService;
import com.xddcodec.fs.system.service.SysWorkspaceService;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.xddcodec.fs.system.domain.table.SysRoleTableDef.SYS_ROLE;
import static com.xddcodec.fs.system.domain.table.SysUserTableDef.SYS_USER;
import static com.xddcodec.fs.system.domain.table.SysWorkspaceInvitationTableDef.SYS_WORKSPACE_INVITATION;

/**
 * 工作空间邀请服务实现
 *
 * @Author: xddcode
 * @Date: 2026/3/31
 */
@Service
@RequiredArgsConstructor
public class SysWorkspaceInvitationServiceImpl extends ServiceImpl<SysWorkspaceInvitationMapper, SysWorkspaceInvitation> implements SysWorkspaceInvitationService {

    private final Converter converter;
    private final SysWorkspaceMemberService memberService;
    private final SysWorkspaceService workspaceService;

    @Override
    public List<WorkspaceInvitationVO> getInvitations(String workspaceId) {
        return mapper.selectListByQueryAs(
                QueryWrapper.create()
                        .select(
                                SYS_WORKSPACE_INVITATION.ALL_COLUMNS,
                                SYS_ROLE.ROLE_NAME,
                                SYS_USER.NICKNAME.as("invitedByName")
                        )
                        .from(SYS_WORKSPACE_INVITATION)
                        .leftJoin(SYS_ROLE).on(SYS_WORKSPACE_INVITATION.ROLE_ID.eq(SYS_ROLE.ID))
                        .leftJoin(SYS_USER).on(SYS_WORKSPACE_INVITATION.INVITED_BY.eq(SYS_USER.ID))
                        .where(SYS_WORKSPACE_INVITATION.WORKSPACE_ID.eq(workspaceId))
                        .and(SYS_WORKSPACE_INVITATION.STATUS.in(0, 1)) // 只显示待接受和已接受的
                        .orderBy(SYS_WORKSPACE_INVITATION.CREATED_AT.desc()),
                WorkspaceInvitationVO.class
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceInvitationVO createInvitation(String workspaceId, CreateInvitationCmd cmd, String inviterId) {
        // 生成邀请令牌
        String token = UUID.randomUUID().toString().replace("-", "");

        // 创建邀请
        SysWorkspaceInvitation invitation = new SysWorkspaceInvitation();
        invitation.setWorkspaceId(workspaceId);
        invitation.setEmail(cmd.getEmail());
        invitation.setRoleId(cmd.getRoleId());
        invitation.setInvitedBy(inviterId);
        invitation.setToken(token);
        invitation.setStatus(0); // 待接受
        invitation.setExpiresAt(LocalDateTime.now().plusHours(cmd.getExpiresHours()));
        this.save(invitation);

        // TODO: 发送邀请邮件

        return converter.convert(invitation, WorkspaceInvitationVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelInvitation(String invitationId, String workspaceId) {
        SysWorkspaceInvitation invitation = this.getById(invitationId);
        if (invitation == null) {
            throw new BusinessException(I18nUtils.getMessage("invitation.not.exist"));
        }

        if (!invitation.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(I18nUtils.getMessage("invitation.no.permission"));
        }

        // 更新状态为已取消
        invitation.setStatus(3);
        this.updateById(invitation);
    }

    @Override
    public SysWorkspaceInvitation findByToken(String token) {
        return this.getOne(QueryWrapper.create()
                .where(SYS_WORKSPACE_INVITATION.TOKEN.eq(token)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptInvitation(String token, String userId) {
        SysWorkspaceInvitation invitation = findByToken(token);
        if (invitation == null) {
            throw new BusinessException(I18nUtils.getMessage("invitation.not.exist"));
        }

        // 检查邀请状态
        if (invitation.getStatus() != 0) {
            throw new BusinessException(I18nUtils.getMessage("invitation.invalid"));
        }

        // 检查是否过期
        if (invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
            invitation.setStatus(2); // 已过期
            this.updateById(invitation);
            throw new BusinessException(I18nUtils.getMessage("invitation.expired"));
        }

        // 检查是否已经是成员
        SysWorkspaceMember existingMember = memberService.findByWorkspaceAndUser(invitation.getWorkspaceId(), userId);
        if (existingMember != null) {
            throw new BusinessException(I18nUtils.getMessage("invitation.already.member"));
        }

        // 创建成员记录
        CreateWorkspaceMemberCmd memberCmd = new CreateWorkspaceMemberCmd();
        memberCmd.setWorkspaceId(invitation.getWorkspaceId());
        memberCmd.setUserId(userId);
        memberCmd.setRoleId(invitation.getRoleId());
        memberService.createMember(memberCmd);

        // 更新邀请状态
        invitation.setStatus(1); // 已接受
        this.updateById(invitation);

        // 更新工作空间成员数量
        SysWorkspace workspace = workspaceService.getById(invitation.getWorkspaceId());
        if (workspace != null) {
            workspace.setMemberCount(workspace.getMemberCount() + 1);
            workspaceService.updateById(workspace);
        }
    }
}
