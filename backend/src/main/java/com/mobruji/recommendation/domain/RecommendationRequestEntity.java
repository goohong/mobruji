package com.mobruji.recommendation.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import com.mobruji.song.domain.Mood;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추천 요청 1건 (영속화 단위). 이름이 `RecommendationRequestDto`와 헷갈리지 않도록 `Entity` 접미사.
 *
 * <p>{@code excludeSongIds}는 별 join table {@code recommendation_request_exclude_song}로 영속한다.
 * 정규형 + 곡 ID별 row → 향후 분석 쿼리(어떤 곡이 자주 제외되는지 등) 용이. JSON column / comma-string 대안
 * 대비 H2 호환과 정규형을 모두 만족. 자세한 결정 근거는
 * {@code docs/features/recommendation-algorithm-v1.md} §9 결정 로그.
 */
@Getter
@Entity
@Table(name = "recommendation_request", indexes = @Index(name = "ix_recommendation_request_session_created", columnList = "session_id, created_at"))
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "voice_range_low", nullable = false)
    private int voiceRangeLow;

    @Column(name = "voice_range_high", nullable = false)
    private int voiceRangeHigh;

    @Enumerated(EnumType.STRING)
    @Column(name = "mood", length = 16)
    private Mood mood;

    /**
     * v2(#218)에서 추가된 사용자 선호 BPM. nullable — 미입력 시 mood 기반 default 사용.
     * tempoMatch 신호 계산과 결정성 seed에 모두 영향.
     */
    @Column(name = "preferred_bpm")
    private Integer preferredBpm;

    /**
     * #1487에서 추가된 요청자 연령대. nullable — 미입력 시 generationFit 신호가 0 이 되어 랭킹에 영향이 없다.
     * 결정성 seed 입력에도 포함된다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "age_group", length = 16)
    private AgeGroup ageGroup;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 사용자가 결과에서 제외하길 원한 곡 ID 목록. 입력 시점 그대로 영속하며, 순서/중복은 클라이언트 입력을 보존한다.
     *
     * <p>저장 테이블: {@code recommendation_request_exclude_song(recommendation_request_id, song_id)}.
     * FK 제약은 두지 않는다(다른 도메인 join들과 일관성 — application 레벨 join).
     *
     * <p>fetch는 EAGER. 본 컬렉션은 (a) 요청과 의미적으로 한 묶음이고 (b) 곡 ID만 담는 작은 정수 컬렉션이며
     * (c) `findById` 호출 측에서 항상 함께 필요한 메타데이터라 LAZY로 두면 트랜잭션 경계 밖 접근 시
     * `LazyInitializationException`만 늘린다. 다른 도메인 join도 application 레벨이라 트랜잭션 분리 이점이 없다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "recommendation_request_exclude_song", joinColumns = @JoinColumn(name = "recommendation_request_id", nullable = false), indexes = @Index(
                    name = "ix_rec_req_exclude_song_request", columnList = "recommendation_request_id"))
    @Column(name = "song_id", nullable = false)
    private List<Long> excludeSongIds = new ArrayList<>();

    public static RecommendationRequestEntity create(
            final String sessionId,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood mood,
            final Integer preferredBpm,
            final AgeGroup ageGroup,
            final List<Long> excludeSongIds) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(excludeSongIds, "excludeSongIds must not be null");
        if (voiceRangeLow > voiceRangeHigh) {
            throw new IllegalArgumentException(
                    "voiceRangeLow (" + voiceRangeLow + ") must be <= voiceRangeHigh (" + voiceRangeHigh + ")");
        }
        if (preferredBpm != null && (preferredBpm < 30 || preferredBpm > 300)) {
            throw new IllegalArgumentException(
                    "preferredBpm out of plausible range [30, 300]: " + preferredBpm);
        }
        // 입력 리스트의 외부 변형으로부터 엔티티 내부 상태를 보호하기 위해 방어적 복사.
        return new RecommendationRequestEntity(
                null,
                sessionId,
                voiceRangeLow,
                voiceRangeHigh,
                mood,
                preferredBpm,
                ageGroup,
                LocalDateTime.now(),
                new ArrayList<>(excludeSongIds));
    }
}
