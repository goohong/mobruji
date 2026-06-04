package com.mobruji.recommendation.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

import com.mobruji.recommendation.domain.FeedbackReaction;
import com.mobruji.recommendation.domain.FilterRelaxation;
import com.mobruji.recommendation.domain.Recommendation;
import com.mobruji.recommendation.domain.RecommendationNotFoundException;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.recommendation.domain.SeedSongsNotFoundException;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.recommendation.infrastructure.SessionFeedbackRepository;

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
    private final SeedSongProfiler seedSongProfiler;
    private final SessionFeedbackRepository sessionFeedbackRepository;

    /**
     * "부른 곡 기반 다음곡 추천"(#1486). 사용자가 부른 곡({@code seedSongIds})에서 음역대·분위기·BPM 을
     * 도출해 이어 부르기 좋은 다음 곡을 추천한다. 쇼츠식 스와이프 선곡(#1489)의 백엔드 진입점.
     *
     * <p>구현: seed 곡을 1쿼리로 조회 → {@link SeedSongProfiler} 로 추천 입력을 도출 → 부른 곡(seed)을
     * 결과에서 자동 제외하도록 {@code excludeSongIds} 에 합친 뒤 {@link #create} 파이프라인을 그대로 재사용한다.
     * 별도 점수 함수를 두지 않아 스코어링·다양성·영속·결정성 로직이 단일 경로로 유지된다.
     *
     * <p>스와이프 세션 반응 결합(#1545): {@code useSessionFeedback}(기본 true) 이면 서버에 저장된 세션 반응을
     * 추가 신호로 합친다 — {@code LIKE} 곡을 부른곡 시드와 함께 선호 집합({@link SeedSongProfiler} 입력)으로,
     * {@code PASS} 곡을 회피/제외 집합으로 합쳐 후보를 재정렬·필터한다. 반응이 0건이면 기여 0(콜드스타트 —
     * 기존 결과 하위호환). 결합도 정렬·중복 제거로 결정성을 보존한다.
     *
     * <p>요청한 {@code seedSongIds}(+ 결합한 LIKE 곡) 가 카탈로그에서 하나도 조회되지 않으면 추천을 만들 수 없으므로
     * {@link SeedSongsNotFoundException}(422) 을 던진다.
     */
    public RecommendationResult createFromSeeds(final NextRecommendationCommand nextRecommendationCommand) {
        // 선호 집합 = 부른곡 시드 + 세션 LIKE 곡 / 회피 집합 = 명시 제외 + 세션 PASS 곡.
        // 삽입 순서를 보존하되 LinkedHashSet 으로 중복을 제거한다(결정성은 SeedDeriver 정렬이 최종 보존).
        final List<Long> preferenceSeedIds = new ArrayList<>(
                new LinkedHashSet<>(nextRecommendationCommand.seedSongIds()));
        final List<Long> avoidanceExcludeIds = new ArrayList<>(
                new LinkedHashSet<>(nextRecommendationCommand.excludeSongIds()));
        if (nextRecommendationCommand.useSessionFeedback()) {
            mergeSessionFeedbackSignals(
                    nextRecommendationCommand.sessionId(), preferenceSeedIds, avoidanceExcludeIds);
        }

        final List<Song> seedSongs = songRepository.findAllById(preferenceSeedIds);
        if (seedSongs.isEmpty()) {
            throw new SeedSongsNotFoundException(preferenceSeedIds);
        }

        // 부른 곡(seed)은 결과에서 자동 제외 — 방금 부른/좋아요한 시드 곡을 다시 추천하지 않는다.
        // 회피 집합과 seed 를 합쳐 중복 제거(순서 무관, 결정성은 SeedDeriver 가 정렬로 보존).
        final List<Long> mergedExcludeIds = new ArrayList<>(new LinkedHashSet<>(avoidanceExcludeIds));
        for (final Long seedSongId : preferenceSeedIds) {
            if (!mergedExcludeIds.contains(seedSongId)) {
                mergedExcludeIds.add(seedSongId);
            }
        }

        final CreateRecommendationCommand derivedCommand = seedSongProfiler.profile(
                nextRecommendationCommand.sessionId(), seedSongs, mergedExcludeIds,
                nextRecommendationCommand.excludeSessionHistory());
        // 무한 스와이프(#1763)는 본/패스한 곡을 excludeSongIds 에 누적해 다음 batch 를 이어 붙인다. 풀이 소진되면
        // 빈 응답으로 종료해야 프론트 무한스크롤이 멈춘다(#1817). 0건 fallback(#1668)을 끄면 이미 본 곡을 다시
        // 노출(재surface)하지 않고 빈 결과로 끝낸다 — #1668 "가까운 곡" UX 는 재추천(create) 버튼 경로에만 둔다.
        return create(derivedCommand, false);
    }

    /**
     * 세션 스와이프 반응(#1545)을 결합 신호로 누적한다. {@code LIKE} 곡은 선호 시드 집합 끝에, {@code PASS} 곡은
     * 회피 제외 집합 끝에 추가한다(이미 있으면 skip). 각 reaction 은 인덱스 1쿼리(오래된순)로 조회해 N+1 을 피한다.
     */
    private void mergeSessionFeedbackSignals(
            final String sessionId,
            final List<Long> preferenceSeedIds,
            final List<Long> avoidanceExcludeIds) {
        sessionFeedbackRepository
                .findBySessionIdAndReactionOrderByCreatedAtAsc(sessionId, FeedbackReaction.LIKE)
                .forEach(feedback -> {
                    if (!preferenceSeedIds.contains(feedback.getSongId())) {
                        preferenceSeedIds.add(feedback.getSongId());
                    }
                });
        sessionFeedbackRepository
                .findBySessionIdAndReactionOrderByCreatedAtAsc(sessionId, FeedbackReaction.PASS)
                .forEach(feedback -> {
                    if (!avoidanceExcludeIds.contains(feedback.getSongId())) {
                        avoidanceExcludeIds.add(feedback.getSongId());
                    }
                });
    }

    /**
     * 추천 생성(create) 공개 진입점. 클라이언트가 제외 곡을 명시하지 않은 빈 exclude(=재추천 버튼 첫 호출)면 결과 0건일 때
     * 제외 필터를 단계적으로 완화해 가까운 곡으로 채우는 0건 fallback(#1668)을 적용한다(빈 화면 방지 UX).
     *
     * <p>반대로 클라이언트가 제외 곡을 누적해 보내면(=리스트 무한스크롤 페이지네이션 맥락, #1835) fallback 을 끈
     * {@link #create(CreateRecommendationCommand, boolean)} 로 위임해, 풀 소진 시 이미 본 곡을 재노출(재surface)하지 않고
     * 빈 응답으로 종료시킨다 — 무한 스와이프(#1763) /next 경로(#1817)와 동일한 계약이다. 세션 단위 자동 누적(#1549)이
     * 켜져도 분기는 클라이언트가 직접 보낸 {@code excludeSongIds} 만으로 판정해 재추천 버튼의 하위호환을 보존한다.
     */
    public RecommendationResult create(final CreateRecommendationCommand createRecommendationCommand) {
        final boolean relaxOnZeroResult = createRecommendationCommand.excludeSongIds().isEmpty();
        return create(createRecommendationCommand, relaxOnZeroResult);
    }

    /**
     * {@code relaxOnZeroResult} 가 {@code true} 면 결과 0건일 때 제외 필터를 단계적으로 완화하는 fallback(#1668)을
     * 적용한다. {@code false} 면 완화 없이 빈 결과를 그대로 돌려준다 — 무한 스와이프(#1763) /next 경로(#1817) 또는 리스트
     * 무한스크롤 페이지네이션(#1835)에서 본/패스한 곡 풀이 소진되면 이미 본 곡을 재노출하지 않고 빈 응답으로 종료시키기 위함이다.
     */
    private RecommendationResult create(
            final CreateRecommendationCommand createRecommendationCommand, final boolean relaxOnZeroResult) {
        final long startNanos = System.nanoTime();
        // 세션 단위 자동 중복 회피(#1549): 플래그가 켜지면 같은 세션의 이전 추천 결과 곡 + 이전 제외/부른 곡을
        // 클라이언트가 넘긴 excludeSongIds 에 누적 병합한다. 병합 결과를 영속·필터·seed 에 일관되게 사용해
        // 결정성(같은 입력 → 같은 결과)을 유지한다.
        final List<Long> excludeSongIds = createRecommendationCommand.excludeSessionHistory()
                ? mergeSessionHistoryExcludes(
                        createRecommendationCommand.sessionId(), createRecommendationCommand.excludeSongIds())
                : createRecommendationCommand.excludeSongIds();

        final RecommendationRequestEntity savedRequest = recommendationRequestRepository.save(
                RecommendationRequestEntity.create(
                        createRecommendationCommand.sessionId(),
                        createRecommendationCommand.voiceRangeLow(),
                        createRecommendationCommand.voiceRangeHigh(),
                        createRecommendationCommand.mood(),
                        createRecommendationCommand.preferredBpm(),
                        createRecommendationCommand.ageGroup(),
                        createRecommendationCommand.gender(),
                        excludeSongIds));

        // 후보 곡 단계에서 excludeSongIds 필터링.
        // spec §3 기능 요구사항: "이미 들었어요" → 결과에서 제외.
        // 점수 계산 전에 필터해 점수 산정 비용을 절약하고, 다양성 후처리(아티스트/장르 cap)도 제외 후 카탈로그 위에서 작동.
        // 후보 universe 는 음역대 보유 곡만(#1744): 음역대 미보유 곡은 voiceFit 을 실측 band 로 못 구해 0.5 중립으로
        // 추천 풀을 오염시키므로(추천 곡 전부 50% 사고) 후보에서 제외한다. backfill 완료 곡은 자동 편입된다.
        final Set<Long> excludeSet = new HashSet<>(excludeSongIds);
        final List<Song> rangedSongs = songRepository.findAllWithVocalRange();
        final List<Song> candidates = rangedSongs.stream()
                .filter(song -> !excludeSet.contains(song.getId()))
                .toList();

        // seed에 excludeSongIds를 정렬된 형태로 포함 (누적 패턴: rev 사이클 3 경고).
        // 같은 voiceRange여도 제외 곡 셋이 달라지면 다른 seed → 다른 jitter → 다른 결과.
        final SeedContext seedContext = buildSeedContext(savedRequest, excludeSongIds);
        final int resultCount = recommendationProperties.resultCount();
        final List<FilterRelaxation> relaxedFilters = new ArrayList<>();
        List<ScoredSong> diversified = rankCandidates(candidates, savedRequest, seedContext.seed(), resultCount);

        // 0건 fallback(#1668): 결과가 비면 빈 화면 대신 제외 필터를 가장 덜 침습적인 순서로 완화해 재질의한다.
        // 완화 대상은 제외 곡 셋뿐이다 — 음역대 보유 조건(#1744)은 완화하지 않는다(미보유 곡은 voiceFit 오염원이므로
        // 빈 화면이 더 낫다). 분위기·연령대는 점수 신호일 뿐 후보를 줄이지 않는다.
        // 음역대는 점수 순(가까운 순) 정렬로 끝까지 보존 — 완전 무관 곡이 아닌 가까운 곡부터 노출한다.
        // relaxOnZeroResult=false(무한 스와이프 #1763)면 풀 소진 시 빈 결과로 종료해 재surface 를 막는다(#1817).
        if (relaxOnZeroResult && diversified.isEmpty()) {
            // 1단계: 세션 단위 자동 중복 회피(#1549)로 누적된 제외만 풀고, 사용자가 명시한 제외 곡은 유지한다.
            final Set<Long> clientExcludeSet = new HashSet<>(createRecommendationCommand.excludeSongIds());
            if (clientExcludeSet.size() < excludeSet.size()) {
                final List<Song> sessionHistoryRelaxed = rangedSongs.stream()
                        .filter(song -> !clientExcludeSet.contains(song.getId()))
                        .toList();
                diversified = rankCandidates(sessionHistoryRelaxed, savedRequest, seedContext.seed(), resultCount);
                if (!diversified.isEmpty()) {
                    relaxedFilters.add(FilterRelaxation.SESSION_HISTORY);
                }
            }
            // 2단계: 그래도 0건이면 사용자 명시 제외 곡까지 후보에 포함한다(빈 화면보다는 가까운 곡 노출).
            if (diversified.isEmpty() && !rangedSongs.isEmpty()) {
                diversified = rankCandidates(rangedSongs, savedRequest, seedContext.seed(), resultCount);
                if (!diversified.isEmpty()) {
                    relaxedFilters.add(FilterRelaxation.EXCLUDED_SONGS);
                }
            }
        }

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
                    scoredSong.scored.breakdown(),
                    scoredSong.scored.suggestedTranspose()));
        }
        recommendationRepository.saveAll(recommendationsToPersist);

        // 결정성 관측 로그(spec §3 비기능 (d)).
        // PII 원문(sessionId/음역대 수치)은 로그에 넣지 않는다 — input.hash 로 같은 입력 식별.
        // RANDOM 전략일 때는 seed/hash 가 비결정이므로 hash="-" 로 표기해 운영자가 구분.
        final long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info(
                "event=recommendation.created request.input.hash={} seed={} algoVersion={} resultCount={} "
                        + "relaxed={} relaxedFilters={} durationMs={}",
                seedContext.inputHash(),
                seedContext.seed(),
                ALGO_VERSION,
                recommendations.size(),
                !relaxedFilters.isEmpty(),
                relaxedFilters,
                durationMs);

        return new RecommendationResult(savedRequest.getId(), recommendations, relaxedFilters);
    }

    /**
     * 후보 곡을 점수 내림차순으로 정렬한 뒤 다양성 캡을 적용해 상위 결과를 고른다. {@link #create} 의 정상 경로와
     * 0건 fallback(#1668) 재질의가 같은 산식·결정성을 공유하도록 추출했다.
     *
     * <p>{@code seed} 로 매 호출 새 {@link Random} 을 만들어, 같은 입력(같은 후보·seed)이면 같은 jitter·순서를 보장한다
     * (spec §3 비기능 — 결정성). 후보 리스트는 정렬된 입력 순서를 유지해 jitter 배정이 결정적이다.
     */
    private List<ScoredSong> rankCandidates(
            final List<Song> candidates,
            final RecommendationRequestEntity savedRequest,
            final long seed,
            final int resultCount) {
        final Random random = new Random(seed);
        final List<ScoredSong> scoredSongs = candidates.stream()
                .map(song -> {
                    final Scored scored = recommendationScorer.score(
                            song,
                            savedRequest.getVoiceRangeLow(),
                            savedRequest.getVoiceRangeHigh(),
                            savedRequest.getMood(),
                            savedRequest.getPreferredBpm(),
                            savedRequest.getAgeGroup(),
                            savedRequest.getGender(),
                            random);
                    return new ScoredSong(song, scored);
                })
                .sorted(Comparator.comparingDouble((ScoredSong scoredSong) -> scoredSong.scored.total()).reversed())
                .toList();
        return diversityPostProcessor.apply(scoredSongs, resultCount);
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

    /**
     * 단일 추천 결과를 영속 row 기반으로 다시 조회한다 (history 단건 / 공유 링크 재방문 등).
     *
     * <p>spec: {@code docs/features/recommendation-history-and-feedback.md} — 추천 결과 영속 재조회.
     *
     * <p>Song-누락 정책 (이슈 #599, 옵션 (a) 필터링):
     * persisted row 의 {@code songId} 가 카탈로그에서 사라진 경우(시드 재구성/곡 비공개 전환 등)
     * 해당 entry 를 응답에서 skip 하고 짧아진 리스트를 반환한다.
     * {@link #readHistoryBySessionId(String)} 와 같은 정책으로 통일 — read-side 일관성.
     * 누락이 발생하면 {@code event=recommendation.readById.song_missing} 경고 로그로 운영 가시성을 남긴다.
     * (대안 (b) 명시적 예외는 UX 비용이 더 크다고 판단, (a) 채택. PII/sessionId 원문은 로그에 넣지 않는다.)
     */
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
                .filter(recommendation -> songsById.get(recommendation.getSongId()) != null)
                .map(recommendation -> new ScoredRecommendation(
                        songsById.get(recommendation.getSongId()),
                        recommendation.getScore(),
                        recommendation.getMatchReason(),
                        recommendation.getRankPosition()))
                .toList();
        if (recommendations.size() < persisted.size()) {
            log.warn(
                    "event=recommendation.readById.song_missing requestId={} persisted={} returned={}",
                    requestId,
                    persisted.size(),
                    recommendations.size());
        }
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
                savedRequest.getAgeGroup(),
                savedRequest.getGender(),
                excludeSongIds);
        final String inputHash = SeedDeriver.hashHex16(
                savedRequest.getSessionId(),
                savedRequest.getVoiceRangeLow(),
                savedRequest.getVoiceRangeHigh(),
                savedRequest.getMood(),
                savedRequest.getPreferredBpm(),
                savedRequest.getAgeGroup(),
                savedRequest.getGender(),
                excludeSongIds);
        return new SeedContext(seed, inputHash);
    }

    /**
     * 세션 단위 자동 중복 회피(#1549): 같은 세션의 이전 추천 결과 곡 + 이전 제외/부른 곡을 클라이언트 제외 목록에
     * 누적 병합한다.
     *
     * <p>병합 순서: (1) 클라이언트가 명시한 {@code clientExcludeSongIds} 를 앞에 두고 (2) 이전 추천 결과 곡,
     * (3) 이전 제외/부른 곡 순으로 {@link LinkedHashSet} 에 누적해 중복을 제거한다. 두 history 쿼리는 각각
     * {@code DISTINCT} 1쿼리(N+1 없음). 최종 순서는 결정성에 영향이 없다 — {@link SeedDeriver} 가 seed 도출 시
     * 정렬로 보존하고, 후보 필터는 {@link HashSet} membership 만 사용하기 때문이다.
     */
    private List<Long> mergeSessionHistoryExcludes(
            final String sessionId, final List<Long> clientExcludeSongIds) {
        final Set<Long> accumulated = new LinkedHashSet<>(clientExcludeSongIds);
        accumulated.addAll(recommendationRepository.findDistinctRecommendedSongIdsBySessionId(sessionId));
        accumulated.addAll(recommendationRequestRepository.findDistinctExcludeSongIdsBySessionId(sessionId));
        return new ArrayList<>(accumulated);
    }

    record ScoredSong(Song song, Scored scored) {
    }

    /**
     * 결정성 로그 + jitter Random 구성에 함께 쓰는 seed/hash 쌍.
     */
    private record SeedContext(long seed, String inputHash) {
    }
}
