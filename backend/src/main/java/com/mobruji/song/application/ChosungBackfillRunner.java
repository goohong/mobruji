package com.mobruji.song.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * 초성 파생 컬럼 마이그레이션(V11) 이전 적재된 기존 곡의 {@code titleChosung}/{@code artistChosung}
 * 를 부팅 시 1회성으로 채운다 — 한글 음절 → 초성 파생은 SQL CASE 로 불가하므로 Java 로 수행
 * (spec {@code song-search-and-filter.md} §5-5).
 *
 * <p>{@link Song#backfillChosung()} 가 멱등 SoT — 이미 채워진 row 는 보존. {@link SongSeedLoader}
 * 이후 실행되도록 {@link Order} 를 뒤로 둔다(신규 시드는 {@code Song.create()} 에서 이미 파생되므로
 * 순서 무관하지만 보수적으로 정렬). test 프로파일은 자체 데이터를 셋업하므로 제외.
 */
@Slf4j
@Component
@Order(100)
@Profile("!test")
@RequiredArgsConstructor
public class ChosungBackfillRunner implements ApplicationRunner {

    private final SongRepository songRepository;

    @Override
    @Transactional
    public void run(final ApplicationArguments args) {
        int backfilled = 0;
        for (final Song song : songRepository.findAll()) {
            if (song.backfillChosung()) {
                songRepository.save(song);
                backfilled++;
            }
        }
        log.info("Chosung backfill done: backfilled={}", backfilled);
    }
}
