-- 角色类型：0 系统预设（随空间创建，权限固定），1 自定义
ALTER TABLE `sys_role`
    ADD COLUMN `role_type` tinyint NOT NULL DEFAULT 1 COMMENT '0=系统预设 1=自定义' AFTER `description`;

-- 已有数据：预设编码标为系统角色
UPDATE `sys_role`
SET `role_type` = 0
WHERE LOWER(`role_code`) IN ('admin', 'member', 'viewer');
