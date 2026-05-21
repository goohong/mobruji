package com.mobruji.voice;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceRangeRepository extends JpaRepository<VoiceRange, Long> {

    Optional<VoiceRange> findBySessionId(String sessionId);
}
