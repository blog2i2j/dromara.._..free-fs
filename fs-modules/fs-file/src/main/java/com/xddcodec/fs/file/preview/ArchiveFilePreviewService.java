package com.xddcodec.fs.file.preview;

import com.xddcodec.fs.file.domain.FileInfo;
import com.xddcodec.fs.file.service.FileInfoService;
import com.xddcodec.fs.framework.common.constant.RedisKey;
import com.xddcodec.fs.framework.common.enums.FileTypeEnum;
import com.xddcodec.fs.framework.preview.config.FilePreviewConfig;
import com.xddcodec.fs.framework.preview.core.PreviewContext;
import com.xddcodec.fs.framework.preview.core.PreviewStrategy;
import com.xddcodec.fs.framework.preview.factory.PreviewStrategyManager;
import com.xddcodec.fs.framework.redis.repository.RedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 压缩包内文件预览服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveFilePreviewService {

    private final FileInfoService fileInfoService;
    private final PreviewStrategyManager strategyManager;
    private final FilePreviewConfig previewConfig;
    private final RedisRepository redisRepository;

    private static final String ARCHIVE_INNER_FILE_KEY_PREFIX = "archive:inner:file:";
    private static final long CACHE_EXPIRE_SECONDS = 5 * 60; // 5分钟

    /**
     * 预览压缩包内的文件
     */
    public String previewInnerFile(String archiveFileId, String innerPath, String previewToken, Model model) {
        // 获取压缩包文件信息
        FileInfo archiveFile = fileInfoService.getById(archiveFileId);
        if (archiveFile == null) {
            return buildErrorPage(model, "压缩包不存在", "压缩包文件不存在或已被删除");
        }

        try {
            // 从压缩包中提取文件
            byte[] fileContent = extractFileFromArchive(archiveFile, innerPath);
            if (fileContent == null) {
                return buildErrorPage(model, "文件不存在", "压缩包中未找到指定文件");
            }

            // 获取文件名
            String fileName = getFileNameFromPath(innerPath);
            FileTypeEnum fileType = FileTypeEnum.fromFileName(fileName);
            
            // 获取预览策略
            PreviewStrategy strategy = strategyManager.getStrategy(fileType);
            if (strategy == null) {
                return buildErrorPage(model, "不支持的文件类型", "该文件类型暂不支持在线预览");
            }

            // 生成临时ID并缓存文件内容（使用 Base64 编码避免序列化问题）
            String tempId = UUID.randomUUID().toString().replace("-", "");
            String cacheKey = ARCHIVE_INNER_FILE_KEY_PREFIX + tempId;
            String base64Content = java.util.Base64.getEncoder().encodeToString(fileContent);
            redisRepository.setExpire(cacheKey, base64Content, CACHE_EXPIRE_SECONDS);
            
            // 为临时文件流生成 token（复用同一个 token，但存储 tempId）
            String streamTokenKey = RedisKey.getPreviewTokenKey(previewToken + ":stream");
            redisRepository.setExpire(streamTokenKey, tempId, CACHE_EXPIRE_SECONDS);
            
            // 构建预览上下文 - 使用临时流URL，带上 token
            String streamUrl = previewConfig.getStreamApi() + "/archive/inner/" + tempId 
                    + "?previewToken=" + previewToken + ":stream";
            
            PreviewContext context = PreviewContext.builder()
                    .fileId(tempId)
                    .fileName(fileName)
                    .streamUrl(streamUrl)
                    .fileSize((long) fileContent.length)
                    .extension(getExtension(fileName))
                    .fileType(fileType)
                    .build();

            strategy.fillModel(context, model);
            return strategy.getTemplatePath();

        } catch (Exception e) {
            log.error("提取压缩包内文件失败: archiveFileId={}, innerPath={}", 
                    archiveFileId, innerPath, e);
            return buildErrorPage(model, "提取文件失败", e.getMessage());
        }
    }

    /**
     * 获取缓存的压缩包内文件内容
     */
    public byte[] getCachedInnerFile(String tempId) {
        String cacheKey = ARCHIVE_INNER_FILE_KEY_PREFIX + tempId;
        Object cached = redisRepository.get(cacheKey);
        if (cached == null) {
            return null;
        }
        
        // 如果是 String 类型（Base64 编码），需要解码
        if (cached instanceof String) {
            try {
                return java.util.Base64.getDecoder().decode((String) cached);
            } catch (Exception e) {
                log.error("解码 Base64 失败: tempId={}", tempId, e);
                return null;
            }
        }
        
        // 如果是 byte[] 类型，直接返回
        if (cached instanceof byte[]) {
            return (byte[]) cached;
        }
        
        log.error("缓存数据类型不正确: tempId={}, type={}", tempId, cached.getClass().getName());
        return null;
    }

    /**
     * 从压缩包中提取指定文件
     */
    private byte[] extractFileFromArchive(FileInfo archiveFile, String innerPath) throws Exception {
        String streamUrl = previewConfig.getStreamApi() + "/" + archiveFile.getId();
        String suffix = getExtension(archiveFile.getDisplayName()).toLowerCase();

        try (InputStream inputStream = new BufferedInputStream(
                URI.create(streamUrl).toURL().openStream())) {
            
            return switch (suffix) {
                case "zip" -> extractFromZip(inputStream, innerPath);
                case "tar" -> extractFromTar(inputStream, innerPath);
                case "7z" -> extractFrom7z(inputStream, innerPath);
                default -> throw new IllegalArgumentException("不支持的压缩格式: " + suffix);
            };
        }
    }

    private byte[] extractFromZip(InputStream is, String targetPath) throws IOException {
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(is)) {
            ArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryPath = normalizePath(entry.getName());
                if (entryPath.equals(targetPath) && !entry.isDirectory()) {
                    return readEntryContent(zis);
                }
            }
        }
        return null;
    }

    private byte[] extractFromTar(InputStream is, String targetPath) throws IOException {
        try (TarArchiveInputStream tis = new TarArchiveInputStream(is)) {
            ArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                String entryPath = normalizePath(entry.getName());
                if (entryPath.equals(targetPath) && !entry.isDirectory()) {
                    return readEntryContent(tis);
                }
            }
        }
        return null;
    }

    private byte[] extractFrom7z(InputStream is, String targetPath) throws Exception {
        Path tempPath = null;
        try {
            // 7z 需要随机访问，创建临时文件
            tempPath = Files.createTempFile("extract_7z_", ".tmp");
            Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);

            try (SevenZFile sevenZFile = SevenZFile.builder()
                    .setFile(tempPath.toFile())
                    .get()) {
                
                SevenZArchiveEntry entry;
                while ((entry = sevenZFile.getNextEntry()) != null) {
                    String entryPath = normalizePath(entry.getName());
                    if (entryPath.equals(targetPath) && !entry.isDirectory()) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = sevenZFile.read(buffer)) > 0) {
                            baos.write(buffer, 0, len);
                        }
                        return baos.toByteArray();
                    }
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
        return null;
    }

    private byte[] readEntryContent(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) > 0) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        // 移除末尾的斜杠
        if (path.endsWith("/") || path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String getFileNameFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        path = normalizePath(path);
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String getExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot >= 0) ? fileName.substring(lastDot + 1) : "";
    }

    private String buildErrorPage(Model model, String errorMessage, String errorDetail) {
        model.addAttribute("errorMessage", errorMessage);
        model.addAttribute("errorDetail", errorDetail);
        return "preview/error";
    }
}
