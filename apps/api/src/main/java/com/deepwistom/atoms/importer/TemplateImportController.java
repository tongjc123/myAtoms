package com.deepwistom.atoms.importer;

import com.deepwistom.atoms.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/templates/imports")
public class TemplateImportController {
    private final TemplateImportService imports;
    private final SessionService sessions;

    public TemplateImportController(TemplateImportService imports, SessionService sessions) {
        this.imports = imports;
        this.sessions = sessions;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TemplateImportModels.ImportItem> upload(HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name) throws IOException {
        var item = imports.importDeck(sessions.current(request).id(), name, file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(item);
    }

    @GetMapping
    public TemplateImportModels.ImportPage list(HttpServletRequest request) {
        return imports.list(sessions.current(request).id());
    }
}
