package com.mobruji.recommendation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.song.domain.Mood;

/**
 * {@link RecommendationRequestRepository#findBySessionIdOrderByCreatedAtDescIdDesc(String)} 의
 * N+1 회귀 가드 — closes #409 M5 (rev sub-agent 2026-05-23).
 *
 * <p>{@link com.mobruji.recommendation.domain.RecommendationRequestEntity#excludeSongIds} 는
 * {@code @ElementCollection(EAGER)} 라 default fetch 시 요청 N건 + collection select N건 = N+1 이 발생한다.
 * history GET 응답({@code RecommendationHistoryResponse}) 은 excludeSongIds 를 노출하지 않으므로
 * 해당 collection fetch 는 dead — repository 에 {@code @EntityGraph(attributePaths={})} 를 달아
 * 호출 단위로 LAZY 오버라이드한다.
 *
 * <p>본 테스트는 Hibernate {@link Statistics} 의 prepared statement / collection fetch 카운트로
 * 회귀 가드: 요청 N건이어도 collection select 가 0건이어야 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(RecommendationRequestRepositoryNplus1Test.HibernateStatisticsAutoConfig.class)
class RecommendationRequestRepositoryNplus1Test {

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    @Test
    @DisplayName("findBySessionId 가 요청 3건을 반환할 때 excludeSongIds collection fetch 가 0건 (N+1 회귀 가드)")
    void findBySessionId_doesNotTriggerCollectionFetch_forExcludeSongIds() {
        // given: 같은 sessionId 로 요청 3건 — 각 excludeSongIds 보유
        final String sessionId = "n-plus-one-guard";
        for (int i = 0; i < 3; i++) {
            recommendationRequestRepository.save(RecommendationRequestEntity.create(
                    sessionId, 50, 80, Mood.UPBEAT, null, null, null, List.of(10L + i, 20L + i)));
        }
        // 영속 컨텍스트 비워서 select-from-cache 회피
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        // when
        final List<RecommendationRequestEntity> requests = recommendationRequestRepository
                .findBySessionIdOrderByCreatedAtDescIdDesc(sessionId);

        // then: 요청 select 1회만 발생. excludeSongIds collection fetch 0회.
        assertThat(requests).hasSize(3);
        assertThat(statistics.getPrepareStatementCount())
                .as("요청 조회 select 1회 (collection fetch 발생하면 N+1)")
                .isEqualTo(1L);
        assertThat(statistics.getCollectionFetchCount())
                .as("excludeSongIds collection fetch 는 dead — 0건이어야 한다")
                .isZero();
    }

    /**
     * {@link DataJpaTest} 는 default 로 Hibernate Statistics 가 꺼져 있다. test 컨텍스트에서만 켜기 위해
     * application-test.yml 을 건드리지 않고 본 테스트 로컬 설정으로 활성화한다 (다른 테스트 부작용 차단).
     */
    static class HibernateStatisticsAutoConfig {
    }
}
