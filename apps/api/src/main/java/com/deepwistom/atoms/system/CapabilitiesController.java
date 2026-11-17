package com.deepwistom.atoms.system;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CapabilitiesController {
    @GetMapping("/api/system/capabilities")
    public ResponseEntity<Capabilities> capabilities() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new Capabilities(
                        true,
                        true,
                        "",
                        "网页与游戏支持文本模型生成和修改，视频由火山方舟模型生成，幻灯片由本地排版引擎生成；模型未开通时会如实报错。",
                        List.of("WEB", "GAME", "SLIDES", "VIDEO")));
    }

    public record Capabilities(
            boolean generationAvailable,
            boolean projectManagementAvailable,
            String reasonCode,
            String message,
            List<String> projectTypes) {}
}
