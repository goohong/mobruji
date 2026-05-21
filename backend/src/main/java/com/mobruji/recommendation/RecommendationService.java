package com.mobruji.recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.recommendation.RecommendationScorer.ScoreBreakdown;
import com.mobruji.recommendation.dto.RecommendationCreateRequest;
import com.mobruji.recommendation.dto.RecommendationResponse;
import com.mobruji.recommendation.dto.RecommendedSongResponse;
import com.mobruji.song.Song;
import com.mobruji.song.SongRepository;
import com.mobruji.song.dto.SongResponse;

import lombok.RequiredArgsConstructor;

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
        final RecommendationRequestEntity savedRequest = recommendationRequestRepository.save(
                RecommendationRequestEntity.create(
                        recommendationCreateRequest.sessionId(),
                        recommendationCreateRequest.voiceRangeLow(),
                        recommendationCreateRequest.voiceRangeHigh(),
                        recommendationCreateRequest.mood()));

        final List<Song> allSongs = songRepository.findAll();
        final Random random = new Random();
        final List<ScoredSong> scoredSongs = allSongs.stream()
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
