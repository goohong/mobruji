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

    private final RecommendationRequestRepository recommendationRequestRepository;
    private final RecommendationRepository recommendationRepository;
    private final SongRepository songRepository;
    private final RecommendationScorer recommendationScorer;
    private final DiversityPostProcessor diversityPostProcessor;
    private final RecommendationProperties recommendationProperties;

    public RecommendationResult create(final CreateRecommendationCommand createRecommendationCommand) {
        Objects.requireNonNull(createRecommendationCommand, "createRecommendationCommand must not be null");
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
        final Random random = buildRandom(savedRequest, excludeSongIds);
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

        return new RecommendationResult(savedRequest.getId(), recommendations);
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
     * jitter용 {@link Random}을 생성한다.
     *
     * <p>{@code seedStrategy=DERIVED}(기본)이면 요청 파라미터 해시를 seed로 사용해
     * 같은 입력에 대해 같은 결과를 보장한다 (spec §3 비기능 — 결정성).
     * {@code RANDOM}이면 디버깅 목적 비결정 변주.
     *
     * <p>{@code excludeSongIds}는 seed 입력에 포함된다(누적 패턴). 재추천(같은 voiceRange + 다른 제외 목록)
     * 시에도 결정성을 보존하면서 jitter 변주가 일어나도록 한다.
     */
    private Random buildRandom(final RecommendationRequestEntity savedRequest, final List<Long> excludeSongIds) {
        if (recommendationProperties.seedStrategy() == RecommendationProperties.SeedStrategy.RANDOM) {
            return new Random();
        }
        final long seed = SeedDeriver.derive(
                savedRequest.getSessionId(),
                savedRequest.getVoiceRangeLow(),
                savedRequest.getVoiceRangeHigh(),
                savedRequest.getMood(),
                savedRequest.getPreferredBpm(),
                excludeSongIds);
        return new Random(seed);
    }

    record ScoredSong(Song song, Scored scored) {
    }
}
