package com.mobruji.song;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class SongNotFoundException extends RuntimeException {

    public SongNotFoundException(final Long id) {
        super("Song not found for id: " + id);
    }
}
