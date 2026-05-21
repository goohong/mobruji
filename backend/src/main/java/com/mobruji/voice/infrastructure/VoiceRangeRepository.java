package com.mobruji.voice.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.voice.domain.VoiceRange;

public interface VoiceRangeRepository extends JpaRepository<VoiceRange, Long> {

    Optional<VoiceRange> findBySessionId(String sessionId);
}
