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

import com.mobruji.recommendation.application.RecommendationScorer.ScoreBreakdown;
import com.mobruji.recommendation.api.dto.RecommendationCreateRequest;
import com.mobruji.recommendation.api.dto.RecommendationResponse;
import com.mobruji.recommendation.api.dto.RecommendedSongResponse;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;
import com.mobruji.song.api.dto.SongResponse;

import lombok.RequiredArgsConstructor;

import com.mobruji.recommendation.domain.Recommendation;
import com.mobruji.recommendation.domain.RecommendationNotFoundException;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
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

    public RecommendationResponse create(final RecommendationCreateRequest recommendationCreateRequest) {
        Objects.requireNonNull(recommendationCreateRequest, "recommendationCreateRequest must not be null");
        final List<Long> excludeSongIds = recommendationCreateRequest.excludeSongIdsOrEmpty();

        final RecommendationRequestEntity savedRequest = recommendationRequestRepository.save(
                RecommendationRequestEntity.create(
                        recommendationCreateRequest.sessionId(),
                        recommendationCreateRequest.voiceRangeLow(),
                        recommendationCreateRequest.voiceRangeHigh(),
                        recommendationCreateRequest.mood(),
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
                    final ScoreBreakdown breakdown = recommendationScorer.score(
                            song,
                            savedRequest.getVoiceRangeLow(),
                            savedRequest.getVoiceRangeHigh(),
                            savedRequest.getMood(),
                            random);
                    return new ScoredSong(song, breakdown);
                })
                .sorted(Comparator.comparingDouble((ScoredSong scoredSong) -> scoredSong.breakdown.total()).reversed())
                .toList();

        final int resultCount = recommendationProperties.resultCount();
        final List<ScoredSong> diversified = diversityPostProcessor.apply(scoredSongs, resultCount);

        final List<Recommendation> persisted = new ArrayList<>();
        for (int i = 0; i < diversified.size(); i++) {
            final ScoredSong scoredSong = diversified.get(i);
            final String matchReason = scoredSong.breakdown.toMatchReason(scoredSong.song, savedRequest.getMood());
            persisted.add(recommendationRepository.save(Recommendation.create(
                    savedRequest.getId(),
                    scoredSong.song.getId(),
                    scoredSong.breakdown.total(),
                    matchReason,
                    i + 1)));
        }

        return toResponse(savedRequest.getId(), persisted, diversified);
    }

    @Transactional(readOnly = true)
    public RecommendationResponse readById(final Long requestId) {
        final RecommendationRequestEntity savedRequest = recommendationRequestRepository.findById(requestId)
                .orElseThrow(() -> new RecommendationNotFoundException(requestId));
        final List<Recommendation> persisted = recommendationRepository
                .findByRecommendationRequestIdOrderByRankPositionAsc(savedRequest.getId());
        if (persisted.isEmpty()) {
            return new RecommendationResponse(savedRequest.getId(), List.of());
        }
        final List<Long> songIds = persisted.stream().map(Recommendation::getSongId).toList();
        final Map<Long, Song> songsById = new HashMap<>();
        songRepository.findAllById(songIds).forEach(song -> songsById.put(song.getId(), song));
        final List<RecommendedSongResponse> recommendations = persisted.stream()
                .map(recommendation -> new RecommendedSongResponse(
                        SongResponse.from(songsById.get(recommendation.getSongId())),
                        recommendation.getScore(),
                        recommendation.getMatchReason(),
                        recommendation.getRankPosition()))
                .toList();
        return new RecommendationResponse(savedRequest.getId(), recommendations);
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
                excludeSongIds);
        return new Random(seed);
    }

    private RecommendationResponse toResponse(
            final Long requestId,
            final List<Recommendation> persisted,
            final List<ScoredSong> diversified) {
        final List<RecommendedSongResponse> recommendations = new ArrayList<>();
        for (int i = 0; i < persisted.size(); i++) {
            final Recommendation recommendation = persisted.get(i);
            final ScoredSong scoredSong = diversified.get(i);
            recommendations.add(new RecommendedSongResponse(
                    SongResponse.from(scoredSong.song),
                    recommendation.getScore(),
                    recommendation.getMatchReason(),
                    recommendation.getRankPosition()));
        }
        return new RecommendationResponse(requestId, recommendations);
    }

    record ScoredSong(Song song, ScoreBreakdown breakdown) {
    }
}
