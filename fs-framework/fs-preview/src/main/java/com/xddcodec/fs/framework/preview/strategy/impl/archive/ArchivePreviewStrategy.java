package com.xddcodec.fs.framework.preview.strategy.impl.archive;

import com.xddcodec.fs.framework.common.enums.FileTypeEnum;
import com.xddcodec.fs.framework.preview.core.PreviewContext;
import com.xddcodec.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ArchivePreviewStrategy extends AbstractPreviewStrategy {

    @Override
    public boolean support(FileTypeEnum fileType) {
        return fileType == FileTypeEnum.ARCHIVE;
    }

    @Override
    public String getTemplatePath() {
        return "preview/archive";
    }

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {
        try (InputStream inputStream = new BufferedInputStream(URI.create(context.getStreamUrl()).toURL().openStream())) {

            List<ArchiveFileInfo> files = ArchiveUtil.parseArchive(inputStream, context.getFileName());
            List<ArchiveTreeNode> treeNodes = ArchiveUtil.convertToTree(files);
            String archiveType = ArchiveUtil.detectArchiveType(context.getFileName());

            model.addAttribute("archiveTree", treeNodes);
            model.addAttribute("archiveType", archiveType);
            model.addAttribute("archiveFileId", context.getFileId());

        } catch (Exception e) {
            log.error("解析压缩包失败: {}, 错误: {}", context.getFileName(), e.getMessage(), e);
            model.addAttribute("error", "解析失败：" + e.getMessage());
        }
    }
}
