package org.song.forum.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.song.forum.util.Result;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查控制器：验证 Spring MVC + 统一响应 + 安全放行链路是否打通
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "app", "JavaForum",
                "status", "UP",
                "time", LocalDateTime.now().toString()
        ));
    }
}
