-- ============================================================
-- 权限模型简化迁移脚本
-- 将 33 个细粒度权限合并为 5 个：
--   file:read, file:write, file:share, storage:manage, member:manage
-- ============================================================

-- 1. 清空旧权限数据，插入新的 5 条权限
TRUNCATE TABLE `sys_permission`;

INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `module`, `description`, `sort`, `created_at`, `updated_at`) VALUES
('file:read',       '文件读取', '文件管理',   '查看、预览、下载文件',                      1, NOW(), NOW()),
('file:write',      '文件编辑', '文件管理',   '上传、创建文件夹、删除、移动、重命名、收藏、回收站操作', 2, NOW(), NOW()),
('file:share',      '文件分享', '文件管理',   '创建、管理、取消分享链接',                    3, NOW(), NOW()),
('storage:manage',  '存储管理', '存储管理',   '存储源的增删改查及启用禁用',                   4, NOW(), NOW()),
('member:manage',   '成员管理', '系统管理',   '邀请/移除成员、角色管理、权限查看',             5, NOW(), NOW());

-- 2. 更新已有角色的权限映射（将旧权限码映射到新权限码）

-- 2a. 创建临时表保存需要新增的映射关系
CREATE TEMPORARY TABLE tmp_new_mappings AS
SELECT DISTINCT rp.role_id, rp.role_code, m.new_code AS permission_code
FROM `sys_role_permission` rp
INNER JOIN (
    -- 旧权限码 → 新权限码映射
    SELECT 'file:view'           AS old_code, 'file:read'      AS new_code UNION ALL
    SELECT 'file:preview',                    'file:read'               UNION ALL
    SELECT 'file:download',                   'file:read'               UNION ALL
    SELECT 'file:upload_file',                'file:write'              UNION ALL
    SELECT 'file:upload_folder',              'file:write'              UNION ALL
    SELECT 'file:create_folder',              'file:write'              UNION ALL
    SELECT 'file:delete',                     'file:write'              UNION ALL
    SELECT 'file:move',                       'file:write'              UNION ALL
    SELECT 'file:rename',                     'file:write'              UNION ALL
    SELECT 'file:favorite',                   'file:write'              UNION ALL
    SELECT 'recycle:view',                    'file:write'              UNION ALL
    SELECT 'recycle:restore',                 'file:write'              UNION ALL
    SELECT 'recycle:delete',                  'file:write'              UNION ALL
    SELECT 'recycle:clear',                   'file:write'              UNION ALL
    SELECT 'share:view',                      'file:share'              UNION ALL
    SELECT 'share:create',                    'file:share'              UNION ALL
    SELECT 'share:cancel',                    'file:share'              UNION ALL
    SELECT 'share:clear',                     'file:share'              UNION ALL
    SELECT 'storage:view',                    'storage:manage'          UNION ALL
    SELECT 'storage:create',                  'storage:manage'          UNION ALL
    SELECT 'storage:operate',                 'storage:manage'          UNION ALL
    SELECT 'storage:delete',                  'storage:manage'          UNION ALL
    SELECT 'user:view',                       'member:manage'           UNION ALL
    SELECT 'user:invite',                     'member:manage'           UNION ALL
    SELECT 'user:invitation_cancel',          'member:manage'           UNION ALL
    SELECT 'user:role_update',                'member:manage'           UNION ALL
    SELECT 'user:status_update',              'member:manage'           UNION ALL
    SELECT 'user:delete',                     'member:manage'           UNION ALL
    SELECT 'role:view',                       'member:manage'           UNION ALL
    SELECT 'role:create',                     'member:manage'           UNION ALL
    SELECT 'role:update',                     'member:manage'           UNION ALL
    SELECT 'role:delete',                     'member:manage'           UNION ALL
    SELECT 'permission:view',                 'member:manage'
) m ON rp.permission_code = m.old_code;

-- 2b. 清空旧映射数据
TRUNCATE TABLE `sys_role_permission`;

-- 2c. 插入新映射（去重）
INSERT INTO `sys_role_permission` (`role_id`, `role_code`, `permission_code`)
SELECT DISTINCT role_id, role_code, permission_code
FROM tmp_new_mappings;

-- 2d. 清理临时表
DROP TEMPORARY TABLE IF EXISTS tmp_new_mappings;
