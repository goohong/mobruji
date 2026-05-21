package com.mobruji.voice.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.voice.domain.VoiceRangeSnapshot;

public interface VoiceRangeSnapshotRepository extends JpaRepository<VoiceRangeSnapshot, Long> {

    List<VoiceRangeSnapshot> findBySessionIdOrderByMeasuredAtDesc(String sessionId);

    List<VoiceRangeSnapshot> findBySessionIdOrderByMeasuredAtAsc(String sessionId);
}
