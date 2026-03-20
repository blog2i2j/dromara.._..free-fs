package com.xddcodec.fs.framework.preview.strategy.impl.archive;

import com.xddcodec.fs.framework.common.enums.FileTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Slf4j
public class ArchiveUtil {

    public static List<ArchiveFileInfo> parseArchive(InputStream inputStream, String fileName) throws Exception {
        String suffix = getExtension(fileName).toLowerCase();

        // 7z 处理
        if ("7z".equals(suffix)) {
            return parse7z(inputStream);
        }

        // ZIP 和 TAR 处理
        return parseStreamArchive(inputStream, suffix);
    }

    private static List<ArchiveFileInfo> parseStreamArchive(InputStream is, String suffix) throws Exception {
        List<ArchiveFileInfo> fileInfos = new ArrayList<>();
        try (ArchiveInputStream<? extends ArchiveEntry> ais = createInputStreamBySuffix(is, suffix)) {
            ArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                fileInfos.add(buildFileInfo(entry.getName(), entry.getSize(),
                        entry.getLastModifiedDate(), entry.isDirectory()));
            }
        }
        return fileInfos;
    }

    private static ArchiveInputStream<? extends ArchiveEntry> createInputStreamBySuffix(InputStream is, String suffix) {
        return switch (suffix) {
            case "zip" -> new ZipArchiveInputStream(is);
            case "tar" -> new TarArchiveInputStream(is);
            default -> throw new IllegalArgumentException("不支持的压缩流格式: " + suffix);
        };
    }

    private static List<ArchiveFileInfo> parse7z(InputStream is) throws Exception {
        List<ArchiveFileInfo> fileInfos = new ArrayList<>();
        // 7z 必须随机访问，创建临时文件
        java.nio.file.Path tempPath = null;
        try {
            tempPath = Files.createTempFile("preview_7z_", ".tmp");
            Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);

            // 使用推荐的 Builder 模式替代弃用的构造函数
            try (SevenZFile sevenZFile = SevenZFile.builder()
                    .setFile(tempPath.toFile())
                    .get()) {

                SevenZArchiveEntry entry;
                while ((entry = sevenZFile.getNextEntry()) != null) {
                    fileInfos.add(buildFileInfo(entry.getName(), entry.getSize(),
                            entry.getLastModifiedDate(), entry.isDirectory()));
                }
            }
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException e) {
                    log.warn("删除临时文件失败: {}", tempPath, e);
                }
            }
        }
        return fileInfos;
    }

    private static ArchiveFileInfo buildFileInfo(String fullPath, long size, Date modifyTime, boolean isDir) {
        String name = getFileNameFromPath(fullPath);
        return ArchiveFileInfo.builder()
                .name(name)
                .path(fullPath)
                .isDirectory(isDir)
                .size(size < 0 ? 0L : size)
                .modifyTime(modifyTime != null ? modifyTime.getTime() : 0L)
                .extension(isDir ? "" : getExtension(name))
                .build();
    }

    private static String getFileNameFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        if (path.endsWith("/") || path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private static String getExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot >= 0) ? fileName.substring(lastDot + 1) : "";
    }

    /**
     * 检测压缩包类型
     */
    public static String detectArchiveType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "UNKNOWN";
        }
        String extension = getExtension(fileName).toUpperCase();
        return switch (extension) {
            case "ZIP" -> "ZIP";
            case "TAR" -> "TAR";
            case "7Z" -> "7Z";
            case "RAR" -> "RAR";
            case "GZ" -> "GZIP";
            case "BZ2" -> "BZIP2";
            default -> extension.isEmpty() ? "UNKNOWN" : extension;
        };
    }

    public static Map<String, Object> getArchiveStats(List<ArchiveFileInfo> files) {
        long fileCount = files.stream().filter(f -> !f.getIsDirectory()).count();
        long folderCount = files.stream().filter(ArchiveFileInfo::getIsDirectory).count();
        long totalSize = files.stream().mapToLong(f -> f.getSize() == null ? 0 : f.getSize()).sum();

        return Map.of(
                "fileCount", fileCount,
                "folderCount", folderCount,
                "totalSize", totalSize,
                "totalCount", files.size()
        );
    }

    /**
     * 将扁平的文件列表转换为树形结构
     */
    public static List<ArchiveTreeNode> convertToTree(List<ArchiveFileInfo> flatFiles) {
        if (flatFiles == null || flatFiles.isEmpty()) {
            return Collections.emptyList();
        }

        // 按路径排序，确保父目录在子文件之前
        flatFiles.sort(Comparator.comparing(ArchiveFileInfo::getPath));

        List<ArchiveTreeNode> roots = new ArrayList<>();
        // 用于快速查找父节点的 Map：key=fullPath（统一去掉末尾斜杠）, value=TreeNode
        Map<String, ArchiveTreeNode> folderMap = new HashMap<>();

        for (ArchiveFileInfo file : flatFiles) {
            String fullPath = file.getPath();
            boolean isDir = file.getIsDirectory();

            // 统一处理：去掉末尾的斜杠，作为标准化路径
            String normalizedPath = fullPath;
            if (normalizedPath.endsWith("/") || normalizedPath.endsWith("\\")) {
                normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
            }

            // 获取当前节点的名称（最后一级）
            String nodeName = isDir ? getFolderNameOnly(fullPath) : file.getName();

            // 获取后端计算好的 fileType
            String typeCode = isDir ? "dir" : FileTypeEnum.fromSuffix(file.getExtension()).getCode();

            // 创建当前 TreeNode
            ArchiveTreeNode currentNode = ArchiveTreeNode.builder()
                    .name(nodeName)
                    .fullPath(normalizedPath)
                    .isDirectory(isDir)
                    .fileType(typeCode)
                    .build();

            // 寻找父节点
            String parentPath = getParentPath(normalizedPath);

            if (parentPath == null || parentPath.isEmpty() || !folderMap.containsKey(parentPath)) {
                // 是根节点（或没有父目录节点，直接挂在根下）
                roots.add(currentNode);
            } else {
                // 有父节点，挂在父节点下
                ArchiveTreeNode parentNode = folderMap.get(parentPath);
                parentNode.addChild(currentNode);
            }

            // 如果是目录，加入 Map 以供子节点查找（使用标准化路径作为 key）
            if (isDir) {
                folderMap.put(normalizedPath, currentNode);
            }
        }
        
        return roots;
    }

    // 辅助：获取目录本身的名字 (如 "a/b/" -> "b")
    private static String getFolderNameOnly(String path) {
        if (path.endsWith("/") || path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    // 辅助：获取父级路径 (如 "a/b/c.txt" -> "a/b", "a/b/" -> "a")
    private static String getParentPath(String path) {
        // 统一处理掉末尾的斜杠
        if (path.endsWith("/") || path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(0, lastSlash) : null;
    }
}