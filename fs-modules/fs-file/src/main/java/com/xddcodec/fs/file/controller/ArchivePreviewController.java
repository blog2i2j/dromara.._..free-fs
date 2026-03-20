package com.xddcodec.fs.file.controller;

import com.xddcodec.fs.file.preview.ArchiveFilePreviewService;
import com.xddcodec.fs.framework.common.constant.RedisKey;
import com.xddcodec.fs.framework.common.domain.Result;
import com.xddcodec.fs.framework.redis.repository.RedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 压缩包内文件预览控制器
 */
@Slf4j
@Controller
@RequestMapping("/archive")
@RequiredArgsConstructor
public class ArchivePreviewController {

    private final ArchiveFilePreviewService archiveFilePreviewService;
    private final RedisRepository redisRepository;

    /**
     * 获取压缩包内文件预览 token
     * @param archiveFileId 压缩包文件ID
     * @param innerPath 压缩包内文件的路径
     */
    @ResponseBody
    @PostMapping("/preview/token/{archiveFileId}")
    public Result<String> previewToken(
            @PathVariable String archiveFileId,
            @RequestParam String innerPath) {
        
        // 生成 token
        String token = UUID.randomUUID().toString().replace("-", "");
        
        // 将 archiveFileId 和 innerPath 组合存储
        String cacheValue = archiveFileId + "|" + innerPath;
        redisRepository.setExpire(
                RedisKey.getPreviewTokenKey(token),
                cacheValue,
                RedisKey.PREVIEW_TOKEN_EXPIRE
        );
        
        log.info("生成压缩包内文件预览 token: archiveFileId={}, innerPath={}, token={}", 
                archiveFileId, innerPath, token);
        
        return Result.ok(token);
    }

    /**
     * 预览压缩包内的文件
     * @param archiveFileId 压缩包文件ID
     * @param innerPath 压缩包内文件的路径
     * @param previewToken 预览 token
     */
    @GetMapping("/preview/{archiveFileId}")
    public String previewInnerFile(
            @PathVariable String archiveFileId,
            @RequestParam String innerPath,
            @RequestParam(required = false) String previewToken,
            Model model) {
        
        log.info("预览压缩包内文件: archiveFileId={}, innerPath={}, token={}", 
                archiveFileId, innerPath, previewToken);
        
        // 验证 token
        if (previewToken == null || previewToken.isBlank()) {
            model.addAttribute("errorMessage", "预览链接已失效");
            model.addAttribute("errorDetail", "缺少预览 token，请重新打开");
            return "preview/error";
        }
        
        String tokenKey = RedisKey.getPreviewTokenKey(previewToken);
        Object cached = redisRepository.get(tokenKey);
        
        if (cached == null) {
            model.addAttribute("errorMessage", "预览链接已失效");
            model.addAttribute("errorDetail", "为了您的文件安全，请刷新后重试");
            return "preview/error";
        }
        
        // 验证 token 对应的文件信息
        String cacheValue = String.valueOf(cached);
        String expectedValue = archiveFileId + "|" + innerPath;
        
        if (!expectedValue.equals(cacheValue)) {
            log.warn("Token 验证失败: expected={}, actual={}", expectedValue, cacheValue);
            model.addAttribute("errorMessage", "预览链接无效");
            model.addAttribute("errorDetail", "Token 验证失败，请重新打开");
            return "preview/error";
        }
        
        // Token 验证通过，删除 token（一次性使用）
        // redisRepository.del(tokenKey); // 如果需要一次性消费，取消注释
        
        try {
            return archiveFilePreviewService.previewInnerFile(archiveFileId, innerPath, previewToken, model);
        } catch (Exception e) {
            log.error("预览压缩包内文件失败: archiveFileId={}, innerPath={}", 
                    archiveFileId, innerPath, e);
            model.addAttribute("errorMessage", "预览失败");
            model.addAttribute("errorDetail", e.getMessage());
            return "preview/error";
        }
    }
}
