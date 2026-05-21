package com.mobruji.song.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * 시드 JSON(`classpath:/songs-seed.json`)을 부팅 시 row 단위 upsert로 적재한다. 다음 정책으로 idempotent:
 *
 * <ul>
 * <li>(title, artist) 자연키로 기존 row를 찾고, 없으면 insert.</li>
 * <li>이미 존재하면 {@link Song#backfillMissingFields} 로 **null 필드만** 채운다 — 운영 중 수정된
 * 값은 덮지 않는다.</li>
 * </ul>
 *
 * <p>이전에는 `count > 0` 이면 통째로 skip했으나, 새 컬럼(lowMidi/highMidi/difficulty) 추가 후
 * 기존 row가 null로 남아 fe 노출이 깨지는 회귀(#122)가 발생해 row 단위 upsert로 전환했다.
 *
 * <p>테스트(test 프로파일)에서는 자동 적재하지 않는다 — 각 테스트가 자체 데이터를 셋업.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class SongSeedLoader implements ApplicationRunner {

    private static final String SEED_PATH = "songs-seed.json";

    private final SongRepository songRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(final ApplicationArguments args) throws IOException {
        final ClassPathResource resource = new ClassPathResource(SEED_PATH);
        if (!resource.exists()) {
            log.warn("Seed file not found at classpath:/{}, skipping", SEED_PATH);
            return;
        }

        try (InputStream inputStream = resource.getInputStream()) {
            final List<SongSeedEntry> entries = objectMapper.readValue(
                    inputStream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SongSeedEntry.class));
            int inserted = 0;
            int backfilled = 0;
            int unchanged = 0;
            for (final SongSeedEntry entry : entries) {
                Objects.requireNonNull(entry, "seed entry must not be null");
                final Optional<Song> existing = songRepository.findByTitleAndArtist(entry.title(), entry.artist());
                if (existing.isEmpty()) {
                    // lowMidi/highMidi가 둘 다 있으면 Song.create() 내부에서 difficulty 자동 분류.
                    // 시드에 difficulty를 직접 명시하지 않는 이유: 분류 규칙(임계값)은 코드에 단일 소스로 두어
                    // fe와의 1:1 일치를 컴파일 시 강제하기 위함.
                    final Song song = Song.builder()
                            .title(entry.title())
                            .artist(entry.artist())
                            .releaseYear(entry.releaseYear())
                            .keyOriginal(entry.keyOriginal())
                            .bpm(entry.bpm())
                            .mood(entry.mood())
                            .language(entry.language())
                            .genre(entry.genre())
                            .tjNumber(entry.tjNumber())
                            .kyNumber(entry.kyNumber())
                            .metadataSource(MetadataSource.MANUAL_SEED)
                            .lowMidi(entry.lowMidi())
                            .highMidi(entry.highMidi())
                            .build();
                    songRepository.save(song);
                    inserted++;
                } else {
                    final Song song = existing.get();
                    final boolean changed = song.backfillMissingFields(entry.lowMidi(), entry.highMidi(), null);
                    if (changed) {
                        songRepository.save(song);
                        backfilled++;
                    } else {
                        unchanged++;
                    }
                }
            }
            log.info(
                    "Seed loader done: total={}, inserted={}, backfilled={}, unchanged={}",
                    entries.size(), inserted, backfilled, unchanged);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SongSeedEntry(
            String title,
            String artist,
            Integer releaseYear,
            MusicalKey keyOriginal,
            Integer bpm,
            Mood mood,
            String language,
            String genre,
            String tjNumber,
            String kyNumber,
            Integer lowMidi,
            Integer highMidi
    ) {
    }
}
