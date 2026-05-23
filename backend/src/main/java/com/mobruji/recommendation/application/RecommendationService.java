package com.mobruji.recommendation.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.recommendation.application.RecommendationScorer.Scored;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import lombok.RequiredArgsConstructor;

import com.mobruji.recommendation.domain.Recommendation;
import com.mobruji.recommendation.domain.RecommendationNotFoundException;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class RecommendationService {

    /**
     * 결정성 로그({@code event=recommendation.created})에 노출하는 알고리즘 버전.
     *
     * <p>현재 코드는 v1+v2 통합 상태이며, tempoMatch(v2 #218) 가 default 활성화되어 있어 운영 동작 기준 {@code v2}.
     * properties 노출은 yml 변경(보호 영역) 을 동반하므로 1차로 상수 사용. 추후 알고리즘 v3 분기 도입 시
     * {@link RecommendationProperties} 에 별도 필드를 추가해 결정 로그와 함께 갱신한다.
     *
     * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 (d) 결정성 로그.
     */
    private static final String ALGO_VERSION = "v2";

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final RecommendationRequestRepository recommendationRequestRepository;
    private final RecommendationRepository recommendationRepository;
    private final SongRepository songRepository;
    private final RecommendationScorer recommendationScorer;
    private final DiversityPostProcessor diversityPostProcessor;
    private final RecommendationProperties recommendationProperties;

    public RecommendationResult create(final CreateRecommendationCommand createRecommendationCommand) {
        Objects.requireNonNull(createRecommendationCommand, "createRecommendationCommand must not be null");
        final long startNanos = System.nanoTime();
        final List<Long> excludeSongIds = createRecommendationCommand.excludeSongIds();

        final RecommendationRequestEntity savedRequest = recommendationRequestRepository.save(
                RecommendationRequestEntity.create(
                        createRecommendationCommand.sessionId(),
                        createRecommendationCommand.voiceRangeLow(),
                        createRecommendationCommand.voiceRangeHigh(),
                        createRecommendationCommand.mood(),
                        createRecommendationCommand.preferredBpm(),
                        excludeSongIds));

        // 후보 곡 단계에서 excludeSongIds 필터링.
        // spec §3 기능 요구사항: "이미 들었어요" → 결과에서 제외.
        // 점수 계산 전에 필터해 점수 산정 비용을 절약하고, 다양성 후처리(아티스트/장르 cap)도 제외 후 카탈로그 위에서 작동.
        final Set<Long> excludeSet = new HashSet<>(excludeSongIds);
        final List<Song> allSongs = songRepository.findAll();
        final List<Song> candidates = allSongs.stream()
                .filter(song -> !excludeSet.contains(song.getId()))
                .toList();

        // seed에 excludeSongIds를 정렬된 형태로 포함 (누적 패턴: rev 사이클 3 경고).
        // 같은 voiceRange여도 제외 곡 셋이 달라지면 다른 seed → 다른 jitter → 다른 결과.
        final SeedContext seedContext = buildSeedContext(savedRequest, excludeSongIds);
        final Random random = new Random(seedContext.seed());
        final List<ScoredSong> scoredSongs = candidates.stream()
                .map(song -> {
                    final Scored scored = recommendationScorer.score(
                            song,
                            savedRequest.getVoiceRangeLow(),
                            savedRequest.getVoiceRangeHigh(),
                            savedRequest.getMood(),
                            savedRequest.getPreferredBpm(),
                            random);
                    return new ScoredSong(song, scored);
                })
                .sorted(Comparator.comparingDouble((ScoredSong scoredSong) -> scoredSong.scored.total()).reversed())
                .toList();

        final int resultCount = recommendationProperties.resultCount();
        final List<ScoredSong> diversified = diversityPostProcessor.apply(scoredSongs, resultCount);

        // 결과 row를 개별 save 호출이 아닌 saveAll로 모아 영속한다.
        // - IDENTITY 전략이라 Hibernate JDBC batch insert는 적용되지 않지만,
        //   영속 컨텍스트 flush·dirty check를 결과 size만큼 반복하던 비용은 1회로 모인다.
        // - rev 사이클 11 k6 첫 실행 p95 회귀(279.71ms / 임계 200ms) 회귀 fix.
        //   spec §3 비기능 — p95 200ms.
        final List<Recommendation> recommendationsToPersist = new ArrayList<>(diversified.size());
        final List<ScoredRecommendation> recommendations = new ArrayList<>(diversified.size());
        for (int i = 0; i < diversified.size(); i++) {
            final ScoredSong scoredSong = diversified.get(i);
            final String matchReason = scoredSong.scored.toMatchReason(scoredSong.song, savedRequest.getMood());
            final int rankPosition = i + 1;
            recommendationsToPersist.add(Recommendation.create(
                    savedRequest.getId(),
                    scoredSong.song.getId(),
                    scoredSong.scored.total(),
                    matchReason,
                    rankPosition));
            recommendations.add(new ScoredRecommendation(
                    scoredSong.song,
                    scoredSong.scored.total(),
                    matchReason,
                    rankPosition,
                    scoredSong.scored.breakdown()));
        }
        recommendationRepository.saveAll(recommendationsToPersist);

        // 결정성 관측 로그(spec §3 비기능 (d)).
        // PII 원문(sessionId/음역대 수치)은 로그에 넣지 않는다 — input.hash 로 같은 입력 식별.
        // RANDOM 전략일 때는 seed/hash 가 비결정이므로 hash="-" 로 표기해 운영자가 구분.
        final long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info(
                "event=recommendation.created request.input.hash={} seed={} algoVersion={} resultCount={} durationMs={}",
                seedContext.inputHash(),
                seedContext.seed(),
                ALGO_VERSION,
                recommendations.size(),
                durationMs);

        return new RecommendationResult(savedRequest.getId(), recommendations);
    }

    /**
     * 세션별 추천 히스토리(요청 + 결과 곡 리스트)를 최신순으로 반환한다.
     *
     * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 — GET
     * {@code /api/v1/sessions/{sessionId}/recommendation-history} 백킹.
     *
     * <p>구현 메모:
     * <ul>
     * <li>요청-결과 join 을 N+1 없이 처리하기 위해 (a) 요청을 1쿼리로 가져온 뒤 (b) 결과를 IN-쿼리 1회로 묶어 fetch.</li>
     * <li>곡 메타데이터도 결과 row 전체의 songId 를 모아 1쿼리(`findAllById`) 로 조회.</li>
     * <li>결정성에는 영향 없음 (read-side 전용). 영속된 row 의 rank/score 를 그대로 노출한다.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<RecommendationHistorySnapshot> readHistoryBySessionId(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final List<RecommendationRequestEntity> requests = recommendationRequestRepository
                .findBySessionIdOrderByCreatedAtDescIdDesc(sessionId);
        if (requests.isEmpty()) {
            return List.of();
        }

        final List<Long> requestIds = requests.stream().map(RecommendationRequestEntity::getId).toList();
        final List<Recommendation> allEntries = recommendationRepository.findByRecommendationRequestIdIn(requestIds);
        final Map<Long, List<Recommendation>> entriesByRequestId = new HashMap<>();
        for (final Recommendation entry : allEntries) {
            entriesByRequestId
                    .computeIfAbsent(entry.getRecommendationRequestId(), key -> new ArrayList<>())
                    .add(entry);
        }
        entriesByRequestId.values()
                .forEach(list -> list.sort(Comparator.comparingInt(Recommendation::getRankPosition)));

        final List<Long> allSongIds = allEntries.stream().map(Recommendation::getSongId).distinct().toList();
        final Map<Long, Song> songsById = new HashMap<>();
        if (!allSongIds.isEmpty()) {
            songRepository.findAllById(allSongIds).forEach(song -> songsById.put(song.getId(), song));
        }

        final List<RecommendationHistorySnapshot> snapshots = new ArrayList<>(requests.size());
        for (final RecommendationRequestEntity request : requests) {
            final List<Recommendation> entries = entriesByRequestId.getOrDefault(request.getId(), List.of());
            // 영속된 곡이 (예: 시드 재구성으로) 사라진 경우의 안전망: 해당 entry 는 응답에서 제외.
            // history 의도(=다른 기기에서 같은 결과 보기)에 비추어 곡 메타가 없는 row 를 노출하는 것보다는
            // 누락 표시 없이 skip 하는 편이 UX 가 안전하다고 판단. (spec §3 비기능 — 결정성과 무관.)
            final List<ScoredRecommendation> scored = entries.stream()
                    .filter(entry -> songsById.get(entry.getSongId()) != null)
                    .map(entry -> new ScoredRecommendation(
                            songsById.get(entry.getSongId()),
                            entry.getScore(),
                            entry.getMatchReason(),
                            entry.getRankPosition()))
                    .toList();
            snapshots.add(new RecommendationHistorySnapshot(request, new RecommendationResult(request.getId(),
                    scored)));
        }
        return snapshots;
    }

    /**
     * history GET 응답 1건의 도메인 표현 (요청 엔티티 + 결과). DTO 매핑을 위해 controller 측이 함께 쓰는 값 객체.
     */
    public record RecommendationHistorySnapshot(
            RecommendationRequestEntity request,
            RecommendationResult result
    ) {
    }

    @Transactional(readOnly = true)
    public RecommendationResult readById(final Long requestId) {
        final RecommendationRequestEntity savedRequest = recommendationRequestRepository.findById(requestId)
                .orElseThrow(() -> new RecommendationNotFoundException(requestId));
        final List<Recommendation> persisted = recommendationRepository
                .findByRecommendationRequestIdOrderByRankPositionAsc(savedRequest.getId());
        if (persisted.isEmpty()) {
            return new RecommendationResult(savedRequest.getId(), List.of());
        }
        final List<Long> songIds = persisted.stream().map(Recommendation::getSongId).toList();
        final Map<Long, Song> songsById = new HashMap<>();
        songRepository.findAllById(songIds).forEach(song -> songsById.put(song.getId(), song));
        final List<ScoredRecommendation> recommendations = persisted.stream()
                .map(recommendation -> new ScoredRecommendation(
                        songsById.get(recommendation.getSongId()),
                        recommendation.getScore(),
                        recommendation.getMatchReason(),
                        recommendation.getRankPosition()))
                .toList();
        return new RecommendationResult(savedRequest.getId(), recommendations);
    }

    /**
     * jitter용 seed + 결정성 로그용 input hash 를 같이 도출한다.
     *
     * <p>{@code seedStrategy=DERIVED}(기본)이면 요청 파라미터 해시를 seed로 사용해
     * 같은 입력에 대해 같은 결과를 보장한다 (spec §3 비기능 — 결정성).
     * {@code RANDOM}이면 디버깅 목적 비결정 변주 — seed 는 매 호출 새 {@link Random}, hash 는 "-" 로 표기.
     *
     * <p>{@code excludeSongIds}는 seed 입력에 포함된다(누적 패턴). 재추천(같은 voiceRange + 다른 제외 목록)
     * 시에도 결정성을 보존하면서 jitter 변주가 일어나도록 한다.
     */
    private SeedContext buildSeedContext(
            final RecommendationRequestEntity savedRequest,
            final List<Long> excludeSongIds) {
        if (recommendationProperties.seedStrategy() == RecommendationProperties.SeedStrategy.RANDOM) {
            return new SeedContext(new Random().nextLong(), "-");
        }
        final long seed = SeedDeriver.derive(
                savedRequest.getSessionId(),
                savedRequest.getVoiceRangeLow(),
                savedRequest.getVoiceRangeHigh(),
                savedRequest.getMood(),
                savedRequest.getPreferredBpm(),
                excludeSongIds);
        final String inputHash = SeedDeriver.hashHex16(
                savedRequest.getSessionId(),
                savedRequest.getVoiceRangeLow(),
                savedRequest.getVoiceRangeHigh(),
                savedRequest.getMood(),
                savedRequest.getPreferredBpm(),
                excludeSongIds);
        return new SeedContext(seed, inputHash);
    }

    record ScoredSong(Song song, Scored scored) {
    }

    /**
     * 결정성 로그 + jitter Random 구성에 함께 쓰는 seed/hash 쌍.
     */
    private record SeedContext(long seed, String inputHash) {
    }
}
