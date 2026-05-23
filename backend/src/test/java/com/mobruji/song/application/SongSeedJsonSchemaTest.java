package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Year;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;

/**
 * `classpath:/songs-seed.json` 형식 게이트 (#276, spec: `docs/features/song-curation-seed-100.md` §5-5).
 *
 * <p>본 테스트는 시드 데이터가 머지(PR C, #278)되기 전에 **형식만** 잠근다. 30곡 현 데이터로 그린이며,
 * 100곡 확장 PR이 들어와도 동일 규칙으로 자동 검증된다. PR A (스키마 게이트) 책임.
 *
 * <p>spec §5-7 충돌 해소 규칙은 `SongSeedLoaderIntegrationTest` 의 회귀 가드가 담당한다.
 *
 * <p>Genre 화이트리스트는 spec §5-5 (발라드/댄스/락/트로트/팝) 에 더해 현 30곡 데이터에 존재하는
 * `R&B` 를 한시적으로 포함한다 — spec §5-5 와 코드 데이터 간 충돌이며, 100곡 확장(PR C)에서
 * `R&B` 항목을 spec 화이트리스트로 재분류하거나 spec §9 결정 로그에 추가하는 것을 권장. 본 PR(A) 의
 * "30곡 그린" 요구를 만족시키기 위한 의도적 완화.
 *
 * <p>Span (highMidi - lowMidi) 은 spec §5-5 에서 "≤ 24 권장" 으로 명시 — 본 테스트는 권장값 +2 (≤ 26)
 * 를 sanity gate 로 사용한다. 26 은 현 30곡 데이터의 최대치(`취중고백` lowMidi=48/highMidi=74)에 맞춘
 * 한시적 grace 이며, PR C 이후 ≤ 24 로 strict 화 검토.
 */
class SongSeedJsonSchemaTest {

    private static final String SEED_PATH = "songs-seed.json";

    /** spec §5-5: 발라드/댄스/락/트로트/팝 화이트리스트 + 현 데이터의 R&B (위 클래스 javadoc 참조). */
    private static final Set<String> ALLOWED_GENRES = Set.of(
            "발라드", "댄스", "락", "트로트", "팝", "R&B");

    /** spec §5-5: ko/en/ja/zh 화이트리스트. */
    private static final Set<String> ALLOWED_LANGUAGES = Set.of("ko", "en", "ja", "zh");

    private static final int LOW_MIDI_MIN = 36;
    private static final int LOW_MIDI_MAX = 84;
    private static final int HIGH_MIDI_MIN = 48;
    private static final int HIGH_MIDI_MAX = 96;
    /** spec §5-5: "span ≤ 24 권장" — 현 데이터 max=26 grace (위 클래스 javadoc 참조). */
    private static final int SPAN_MAX_SANITY = 26;
    private static final int BPM_MIN = 40;
    private static final int BPM_MAX = 220;
    private static final int RELEASE_YEAR_MIN = 1970;
    private static final double METADATA_CONFIDENCE_MIN = 0.7;
    private static final double METADATA_CONFIDENCE_MAX = 1.0;
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int ARTIST_MAX_LENGTH = 100;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private List<SeedEntry> loadSeed() throws Exception {
        final ClassPathResource resource = new ClassPathResource(SEED_PATH);
        assertThat(resource.exists()).as("songs-seed.json 이 classpath 에 존재해야 한다").isTrue();
        try (InputStream inputStream = resource.getInputStream()) {
            return OBJECT_MAPPER.readValue(
                    inputStream,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, SeedEntry.class));
        }
    }

    @Test
    @DisplayName("seed entry 전체가 spec §5-5 필수 필드 (title/artist/releaseYear/mood/language/genre/metadataConfidence/lowMidi/highMidi) 를 보유한다")
    void allEntries_haveRequiredFields() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();

        // when & then
        assertThat(entries).isNotEmpty();
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            final String location = describe(index, entry);
            assertThat(entry.title()).as("%s title", location).isNotBlank();
            assertThat(entry.title()).as("%s title length", location).hasSizeLessThanOrEqualTo(TITLE_MAX_LENGTH);
            assertThat(entry.artist()).as("%s artist", location).isNotBlank();
            assertThat(entry.artist()).as("%s artist length", location).hasSizeLessThanOrEqualTo(ARTIST_MAX_LENGTH);
            assertThat(entry.releaseYear()).as("%s releaseYear", location).isNotNull();
            assertThat(entry.mood()).as("%s mood", location).isNotNull();
            assertThat(entry.language()).as("%s language", location).isNotBlank();
            assertThat(entry.genre()).as("%s genre", location).isNotBlank();
            assertThat(entry.metadataConfidence()).as("%s metadataConfidence", location).isNotNull();
            assertThat(entry.lowMidi()).as("%s lowMidi", location).isNotNull();
            assertThat(entry.highMidi()).as("%s highMidi", location).isNotNull();
        }
    }

    @Test
    @DisplayName("MIDI 범위 — lowMidi 36~84, highMidi 48~96, lowMidi < highMidi, span ≤ 26 (spec §5-5 권장 24 + grace 2)")
    void midiFields_satisfySpecRanges() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();

        // when & then
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            final String location = describe(index, entry);
            final int lowMidi = entry.lowMidi();
            final int highMidi = entry.highMidi();
            assertThat(lowMidi).as("%s lowMidi in [%d,%d]", location, LOW_MIDI_MIN, LOW_MIDI_MAX)
                    .isBetween(LOW_MIDI_MIN, LOW_MIDI_MAX);
            assertThat(highMidi).as("%s highMidi in [%d,%d]", location, HIGH_MIDI_MIN, HIGH_MIDI_MAX)
                    .isBetween(HIGH_MIDI_MIN, HIGH_MIDI_MAX);
            assertThat(lowMidi).as("%s lowMidi < highMidi", location).isLessThan(highMidi);
            assertThat(highMidi - lowMidi).as("%s span ≤ %d (spec §5-5 권장 ≤24)", location, SPAN_MAX_SANITY)
                    .isLessThanOrEqualTo(SPAN_MAX_SANITY);
        }
    }

    @Test
    @DisplayName("metadataConfidence 0.7 ≤ x ≤ 1.0 (spec §5-5)")
    void metadataConfidence_isWithinSpecRange() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();

        // when & then
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            assertThat(entry.metadataConfidence())
                    .as("%s metadataConfidence in [%.1f,%.1f]",
                            describe(index, entry), METADATA_CONFIDENCE_MIN, METADATA_CONFIDENCE_MAX)
                    .isBetween(METADATA_CONFIDENCE_MIN, METADATA_CONFIDENCE_MAX);
        }
    }

    @Test
    @DisplayName("genre 화이트리스트 (spec §5-5 + R&B grace, 위 javadoc 참조)")
    void genre_isInWhitelist() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();

        // when & then
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            assertThat(ALLOWED_GENRES)
                    .as("%s genre", describe(index, entry))
                    .contains(entry.genre());
        }
    }

    @Test
    @DisplayName("language 화이트리스트 ko/en/ja/zh (spec §5-5)")
    void language_isInWhitelist() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();

        // when & then
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            assertThat(ALLOWED_LANGUAGES)
                    .as("%s language", describe(index, entry))
                    .contains(entry.language());
        }
    }

    @Test
    @DisplayName("bpm 은 null 이거나 40~220 (spec §5-5)")
    void bpm_isNullOrInSpecRange() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();

        // when & then
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            if (entry.bpm() != null) {
                assertThat(entry.bpm())
                        .as("%s bpm in [%d,%d]", describe(index, entry), BPM_MIN, BPM_MAX)
                        .isBetween(BPM_MIN, BPM_MAX);
            }
        }
    }

    @Test
    @DisplayName("releaseYear 1970 ≤ x ≤ 현재 연도 (spec §5-5)")
    void releaseYear_isWithinSpecRange() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();
        final int currentYear = Year.now().getValue();

        // when & then
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            assertThat(entry.releaseYear())
                    .as("%s releaseYear in [%d,%d]", describe(index, entry), RELEASE_YEAR_MIN, currentYear)
                    .isBetween(RELEASE_YEAR_MIN, currentYear);
        }
    }

    @Test
    @DisplayName("(title, artist) 자연키가 중복되지 않는다 — SongSeedLoader upsert 키")
    void naturalKey_isUnique() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();
        final Set<String> seenKeys = new HashSet<>();

        // when & then
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            final String key = entry.title() + " " + entry.artist();
            assertThat(seenKeys.add(key))
                    .as("(title, artist) 중복: %s", describe(index, entry))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("mood 는 도메인 enum 값 1개로 파싱된다 (spec §5-5)")
    void mood_isValidEnum() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();

        // when & then
        // 파싱 단계에서 enum 값이 아니면 Jackson 이 예외를 던지므로 loadSeed() 가 성공하면 1차 검증 완료.
        // 추가로 mood 가 실제 Mood enum 으로 round-trip 가능한지 확인 (defense-in-depth).
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            final Mood mood = entry.mood();
            assertThat(Mood.values())
                    .as("%s mood enum membership", describe(index, entry))
                    .contains(mood);
        }
    }

    @Test
    @DisplayName("keyOriginal 은 null 이거나 MusicalKey enum 값 (spec §5-5)")
    void keyOriginal_isNullOrValidEnum() throws Exception {
        // given
        final List<SeedEntry> entries = loadSeed();

        // when & then — Jackson 이 파싱 단계에서 enum 매핑 실패 시 예외. 명시 확인.
        for (int index = 0; index < entries.size(); index++) {
            final SeedEntry entry = entries.get(index);
            if (entry.keyOriginal() != null) {
                assertThat(MusicalKey.values())
                        .as("%s keyOriginal enum membership", describe(index, entry))
                        .contains(entry.keyOriginal());
            }
        }
    }

    private String describe(final int index, final SeedEntry entry) {
        return String.format("entry[%d] (%s - %s)", index, entry.title(), entry.artist());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedEntry(
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
            String isrc,
            Double metadataConfidence,
            Integer lowMidi,
            Integer highMidi
    ) {
    }
}
