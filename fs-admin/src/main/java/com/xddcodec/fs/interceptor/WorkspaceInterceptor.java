package com.xddcodec.fs.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.xddcodec.fs.framework.common.constant.CommonConstant;
import com.xddcodec.fs.framework.common.context.WorkspaceContext;
import com.xddcodec.fs.system.mapper.SysWorkspaceMemberMapper;
import com.xddcodec.fs.system.domain.SysWorkspaceMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

/**
 * 工作空间拦截器 - 从请求头提取工作空间ID并验证成员身份
 *
 * @Author: xddcode
 * @Date: 2026/3/31
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceInterceptor implements HandlerInterceptor {

    private final SysWorkspaceMemberMapper memberMapper;

    // 不需要工作空间上下文的白名单
    private static final Set<String> WHITELIST = Set.of(
            "/apis/auth/login",
            "/apis/auth/logout",
            "/apis/user/register",
            "/apis/user/info",
            "/apis/user/password",
            "/apis/user/forget-password",
            "/apis/workspace/list",
            "/apis/workspace/check-slug",
            "/apis/permission/list",
            "/apis/transfer/sse"
    );

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
        String path = req.getRequestURI();

        // 白名单直接放行
        if (WHITELIST.stream().anyMatch(path::startsWith)) {
            return true;
        }

        // POST /apis/workspace（创建工作空间）也不需要
        if ("POST".equals(req.getMethod()) && "/apis/workspace".equals(path)) {
            return true;
        }

        // 获取工作空间ID
        String workspaceId = req.getHeader(CommonConstant.X_WORKSPACE_ID);
        if (workspaceId == null || workspaceId.isBlank()) {
            res.setStatus(400);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":400,\"msg\":\"缺少工作空间ID\"}");
            return false;
        }

        // 校验当前用户是否为该工作空间成员
        if (!StpUtil.isLogin()) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":401,\"msg\":\"未登录\"}");
            return false;
        }

        String userId = StpUtil.getLoginIdAsString();
        SysWorkspaceMember member = memberMapper.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null) {
            res.setStatus(403);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":403,\"msg\":\"无权访问该工作空间\"}");
            return false;
        }

        // 设置工作空间上下文
        WorkspaceContext.setWorkspaceId(workspaceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清除工作空间上下文
        WorkspaceContext.clear();
    }
}
