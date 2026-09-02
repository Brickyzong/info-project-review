package com.xmps.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 文件暂存服务——将上传文件保存到本地磁盘。
 * 生产环境可替换为对象存储（MinIO / OSS），实现统一接口即可。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path uploadDir;

    public FileStorageService() {
        this.uploadDir = Paths.get("uploads");
        try {
            Files.createDirectories(uploadDir);
            log.info("文件存储目录初始化: {}", uploadDir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("无法创建文件存储目录: " + uploadDir, e);
        }
    }

    /**
     * 保存上传文件，返回存储路径。
     *
     * @param file    上传的文件
     * @param taskId  关联任务 ID（用于按任务组织目录）
     * @return 文件在磁盘上的绝对路径
     */
    public String store(MultipartFile file, String taskId) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // 保留原始扩展名
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            ext = originalName.substring(dot);
        }

        // 存储路径：uploads/{taskId}/{uuid}{ext}
        Path taskDir = uploadDir.resolve(taskId);
        try {
            Files.createDirectories(taskDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建任务目录: " + taskDir, e);
        }

        String storedName = UUID.randomUUID().toString() + ext;
        Path dest = taskDir.resolve(storedName);

        try {
            file.transferTo(dest);
            log.info("文件已保存 — taskId={}, original={}, path={}, size={}",
                    taskId, originalName, dest, file.getSize());
            return dest.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + dest, e);
        }
    }

    /**
     * 删除任务对应的全部文件（评审完成后的清理，可选）
     */
    public void deleteByTask(String taskId) {
        Path taskDir = uploadDir.resolve(taskId);
        try {
            if (Files.exists(taskDir)) {
                try (var walk = Files.walk(taskDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                }
                log.info("任务文件已清理 — taskId={}", taskId);
            }
        } catch (IOException e) {
            log.warn("清理任务文件失败 — taskId={}, error={}", taskId, e.getMessage());
        }
    }
}
