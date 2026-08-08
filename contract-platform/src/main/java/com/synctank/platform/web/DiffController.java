package com.synctank.platform.web;

import com.synctank.platform.diff.DiffReport;
import com.synctank.platform.diff.DiffService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/diff")
public class DiffController {

    private final DiffService diffService;

    public DiffController(DiffService diffService) {
        this.diffService = diffService;
    }

    public record DiffRequest(String baseline, String candidate) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public DiffReport diff(@RequestBody DiffRequest request) {
        return diffService.diff(request.baseline(), request.candidate());
    }
}