package com.xddcodec.fs.file.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import com.xddcodec.fs.file.domain.FileInfo;
import com.xddcodec.fs.file.domain.qry.FileRecycleQry;
import com.xddcodec.fs.file.domain.table.FileInfoTableDef;
import com.xddcodec.fs.file.domain.vo.FileRecycleVO;
import com.xddcodec.fs.file.service.FileInfoService;
import com.xddcodec.fs.file.service.FileRecycleService;
import com.xddcodec.fs.file.service.FileUserFavoritesService;
import com.xddcodec.fs.framework.common.domain.PageResult;
import com.xddcodec.fs.framework.common.exception.BusinessException;
import com.xddcodec.fs.storage.facade.StorageServiceFacade;
import com.xddcodec.fs.storage.plugin.core.IStorageOperationService;
import com.xddcodec.fs.storage.plugin.core.context.StoragePlatformContextHolder;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.mybatisflex.core.query.QueryMethods.notExists;
import static com.xddcodec.fs.file.domain.table.FileInfoTableDef.FILE_INFO;

/**
 * 回收站服务接口实现
 *
 * @Author: xddcode
 * @Date: 2025/5/8 9:35
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileRecycleServiceImpl implements FileRecycleService {

    private final Converter converter;

    private final FileInfoService fileInfoService;

    private final FileUserFavoritesService fileUserFavoritesService;

    private final StorageServiceFacade storageServiceFacade;

    @Override
    public PageResult<FileRecycleVO> getRecyclePages(FileRecycleQry qry) {
        String userId = StpUtil.getLoginIdAsString();
        String configId = StoragePlatformContextHolder.getConfigId();
        int pageNum = qry.getPage() == null ? 1 : qry.getPage();
        int pageSize = qry.getPageSize() == null ? 10 : qry.getPageSize();

        FileInfoTableDef t1 = FILE_INFO.as("t1");
        FileInfoTableDef t2 = FILE_INFO.as("t2");

        QueryWrapper queryWrapper = QueryWrapper.create()
                .select(t1.ALL_COLUMNS)
                .from(t1)
                .where(t1.USER_ID.eq(userId))
                .and(t1.STORAGE_PLATFORM_SETTING_ID.eq(configId))
                .and(t1.IS_DELETED.eq(true));

        if (StrUtil.isNotBlank(qry.getKeyword())) {
            String keyword = qry.getKeyword().trim();
            queryWrapper.and(
                    t1.ORIGINAL_NAME.like(keyword)
                            .or(t1.DISPLAY_NAME.like(keyword))
            );
        } else {
            queryWrapper.and(
                    t1.PARENT_ID.isNull()
                            .or(
                                    notExists(
                                            QueryWrapper.create()
                                                    .select(t2.ID)
                                                    .from(t2)
                                                    .where(t2.ID.eq(t1.PARENT_ID))
                                                    .and(t2.IS_DELETED.eq(true))
                                    )
                            )
            );
        }

        queryWrapper.orderBy(FILE_INFO.IS_DIR.desc())
                .orderBy(FILE_INFO.UPDATE_TIME.desc());

        Page<FileInfo> resultPage = fileInfoService.page(new Page<>(pageNum, pageSize), queryWrapper);

        List<FileRecycleVO> voList = converter.convert(resultPage.getRecords(), FileRecycleVO.class);
        return PageResult.success(voList, resultPage.getTotalRow());
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreFiles(List<String> fileIds) {
        if (CollUtil.isEmpty(fileIds)) return;
        String userId = StpUtil.getLoginIdAsString();

        Set<String> allIdsToRestore = collectFileIdsRecursively(
                fileIds,
                userId,
                wrapper -> wrapper.and(FILE_INFO.IS_DELETED.eq(true))
        );

        Set<String> parentIdsInRecycle = collectParentIdsInRecycle(fileIds, userId);
        allIdsToRestore.addAll(parentIdsInRecycle);

        if (CollUtil.isEmpty(allIdsToRestore)) {
            throw new BusinessException("未找到要恢复的文件或文件夹");
        }

        UpdateChain.of(FileInfo.class)
                .set(FileInfo::getIsDeleted, false)
                .set(FileInfo::getDeletedTime, null)
                .where(FILE_INFO.ID.in(allIdsToRestore))
                .and(FILE_INFO.USER_ID.eq(userId))
                .update();

        log.info("用户 {} 恢复文件/文件夹，共 {} 项（含向下级联与向上路径修复）", userId, allIdsToRestore.size());
    }

    /**
     * 向上递归收集：找出这些文件在回收站中的所有祖先文件夹
     */
    private Set<String> collectParentIdsInRecycle(List<String> currentIds, String userId) {
        Set<String> allParentIds = new HashSet<>();
        List<String> runnerIds = new ArrayList<>(currentIds);

        while (CollUtil.isNotEmpty(runnerIds)) {
            // 1. 查出这些文件的 parentId (且 parentId 不为空)
            List<String> pIds = fileInfoService.queryChain()
                    .select(FILE_INFO.PARENT_ID)
                    .where(FILE_INFO.ID.in(runnerIds))
                    .and(FILE_INFO.PARENT_ID.isNotNull())
                    .and(FILE_INFO.USER_ID.eq(userId))
                    .listAs(String.class)
                    .stream().filter(StrUtil::isNotBlank).distinct().collect(Collectors.toList());

            if (CollUtil.isEmpty(pIds)) break;

            // 2. 看看这些 parentId 中，哪些还在回收站里 (is_deleted = true)
            List<String> deletedParents = fileInfoService.queryChain()
                    .select(FILE_INFO.ID)
                    .where(FILE_INFO.ID.in(pIds))
                    .and(FILE_INFO.IS_DELETED.eq(true))
                    .listAs(String.class);

            if (CollUtil.isEmpty(deletedParents)) break;

            // 3. 收集并继续往上找
            allParentIds.addAll(deletedParents);
            runnerIds = deletedParents;
        }
        return allParentIds;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void permanentlyDeleteFiles(List<String> fileIds) {
        if (CollUtil.isEmpty(fileIds)) {
            return;
        }
        String userId = StpUtil.getLoginIdAsString();
        Set<String> allFileIds = collectFileIdsRecursively(
                fileIds,
                userId,
                wrapper -> wrapper.and(FILE_INFO.IS_DELETED.eq(true)) // 只能删除回收站中的
        );
        if (CollUtil.isEmpty(allFileIds)) {
            throw new BusinessException("未找到要删除的文件或文件夹");
        }
        List<FileInfo> allFiles = fileInfoService.listByIds(allFileIds);
        // 找出需要删除物理文件的（没有其他引用的）
        List<FileInfo> physicalFilesToDelete = new ArrayList<>();
        for (FileInfo file : allFiles) {
            if (StrUtil.isBlank(file.getObjectKey())) {
                continue;
            }

            // 查询除了本次要删除的文件外，还有没有其他文件引用这个objectKey
            long count = fileInfoService.count(new QueryWrapper()
                    .where(FILE_INFO.OBJECT_KEY.eq(file.getObjectKey())
                            .and(FILE_INFO.ID.notIn(allFileIds))));

            if (count == 0) {
                physicalFilesToDelete.add(file);
            }
        }

        // 删除文件信息记录
        fileInfoService.removeByIds(allFileIds);

        // 删除用户收藏记录
        fileUserFavoritesService.removeByFileIds(allFileIds, userId);

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (FileInfo file : physicalFilesToDelete) {
                            try {
                                deletePhysicalFile(file);
                            } catch (Exception e) {
                                log.error("删除物理文件失败: {}", file.getObjectKey(), e);
                            }
                        }
                    }
                }
        );
    }

    /**
     * 删除物理文件
     *
     * @param file 文件信息
     */
    private void deletePhysicalFile(FileInfo file) {
        IStorageOperationService storageService = storageServiceFacade.getStorageService(file.getStoragePlatformSettingId());
        storageService.deleteFile(file.getObjectKey());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void clearRecycles() {
        String userId = StpUtil.getLoginIdAsString();
        String configId = StoragePlatformContextHolder.getConfigId();

        FileInfoTableDef t1 = FILE_INFO.as("t1");
        FileInfoTableDef t2 = FILE_INFO.as("t2");

        // 直接从数据库查出所有回收站的“顶层项”ID
        List<String> topLevelIds = fileInfoService.queryChain()
                .select(t1.ID)
                .from(t1)
                .where(t1.USER_ID.eq(userId))
                .and(t1.STORAGE_PLATFORM_SETTING_ID.eq(configId))
                .and(t1.IS_DELETED.eq(true))
                .and(t1.PARENT_ID.isNull().or(
                        notExists(QueryWrapper.create().from(t2).where(t2.ID.eq(t1.PARENT_ID)).and(t2.IS_DELETED.eq(true)))
                ))
                .listAs(String.class);

        if (CollUtil.isNotEmpty(topLevelIds)) {
            this.permanentlyDeleteFiles(topLevelIds);
        }
    }


    /**
     * 递归收集文件ID（通用方法）
     *
     * @param fileIds 初始文件ID列表
     * @param userId  用户ID
     * @param filter  过滤条件（可选）
     * @return 收集到的所有文件ID集合
     */
    private Set<String> collectFileIdsRecursively(
            List<String> fileIds,
            String userId,
            Consumer<QueryWrapper> filter) {

        // 查询初始文件列表
        QueryWrapper initialWrapper = new QueryWrapper()
                .where(FILE_INFO.ID.in(fileIds))
                .and(FILE_INFO.USER_ID.eq(userId));

        // 应用额外过滤条件
        if (filter != null) {
            filter.accept(initialWrapper);
        }

        List<FileInfo> files = fileInfoService.list(initialWrapper);

        if (CollUtil.isEmpty(files)) {
            return Collections.emptySet();
        }

        // 递归收集
        Set<String> allFileIds = new HashSet<>();
        files.forEach(file -> {
            collectFileIdsRecursive(file, allFileIds, userId, filter);
        });

        return allFileIds;
    }

    /**
     * 递归收集单个文件及其子文件的ID
     *
     * @param file       文件信息
     * @param allFileIds 收集的文件ID集合
     * @param userId     用户ID
     * @param filter     过滤条件（可选）
     */
    private void collectFileIdsRecursive(
            FileInfo file,
            Set<String> allFileIds,
            String userId,
            Consumer<QueryWrapper> filter) {

        // 添加当前文件ID
        allFileIds.add(file.getId());

        // 如果是文件夹，递归处理子项
        if (file.getIsDir()) {
            log.debug("收集文件夹 {} 的子项", file.getDisplayName());

            // 构建查询条件
            QueryWrapper wrapper = new QueryWrapper()
                    .where(FILE_INFO.PARENT_ID.eq(file.getId()))
                    .and(FILE_INFO.USER_ID.eq(userId));

            // 应用额外过滤条件
            if (filter != null) {
                filter.accept(wrapper);
            }

            // 查询所有子文件
            List<FileInfo> children = fileInfoService.list(wrapper);

            // 递归收集子项ID
            children.forEach(child -> collectFileIdsRecursive(child, allFileIds, userId, filter));
        }
    }
}
