package com.xddcodec.fs.system.auth.impl;

import com.xddcodec.fs.framework.common.enums.LoginType;
import com.xddcodec.fs.system.auth.LoginStrategy;
import com.xddcodec.fs.system.domain.dto.LoginCmd;
import com.xddcodec.fs.system.domain.vo.LoginResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 邮箱验证码登录策略
 *
 * @Author: xddcode
 * @Date: 2026/4/2 09:59
 */
@Component
@RequiredArgsConstructor
public class EmailLoginStrategy implements LoginStrategy {

    @Override
    public LoginType getLoginType() {
        return LoginType.email_code;
    }

    @Override
    public LoginResult authenticate(LoginCmd cmd) {
        // 1. 从 Redis 校验邮箱验证码
        // 2. 校验通过后根据邮箱查用户
        return new LoginResult();
    }
}
