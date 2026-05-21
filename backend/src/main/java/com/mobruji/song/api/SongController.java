package com.mobruji.song.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.song.api.dto.SongResponse;

import lombok.RequiredArgsConstructor;

import com.mobruji.song.application.SongService;

@RestController
@RequestMapping("/api/v1/songs")
@RequiredArgsConstructor
public class SongController {

    private final SongService songService;

    @GetMapping("/{id}")
    public SongResponse read(@PathVariable final Long id) {
        return songService.readById(id);
    }

    @GetMapping
    public List<SongResponse> search(@RequestParam(name = "keyword", required = false) final String keyword) {
        return songService.searchByKeyword(keyword);
    }
}
