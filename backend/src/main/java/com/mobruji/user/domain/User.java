package com.mobruji.user.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.mobruji.voice.domain.MidiRange;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 영속 사용자 계정 + 음역대/성별 프로필.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491). 익명 sessionId({@link AnonymousSession}) 와 달리 재방문
 * 시에도 음역대/성별을 재입력하지 않도록 프로필을 영속한다 (페르소나 P-A).
 *
 * <ul>
 * <li>{@code LOCAL} 계정은 {@code email} + {@code passwordHash} 로 인증한다 (이메일 소문자 정규화는
 * application 책임).</li>
 * <li>{@code KAKAO}/{@code GOOGLE} 소셜 계정은 {@code (authProvider, providerUserId)} 로 식별하며
 * {@code passwordHash} 는 null — 본 PR 은 스키마/팩토리만 두고 소셜 인증 흐름은 후속 PR.</li>
 * <li>{@code gender}/{@code vocalRangeLowMidi}/{@code vocalRangeHighMidi} 는 선택 프로필. 음역대는
 * 둘 다 있거나 둘 다 없어야 하며, 있으면 {@link MidiRange} 닫힌 구간을 만족해야 한다.</li>
 * </ul>
 */
@Getter
@Entity
@Table(name = "app_user")
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    /** RFC 5321 의 이메일 최대 길이(254). */
    public static final int EMAIL_MAX_LENGTH = 254;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", length = EMAIL_MAX_LENGTH)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 16)
    private AuthProvider authProvider;

    @Column(name = "provider_user_id", length = 128)
    private String providerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 16)
    private UserGender gender;

    @Column(name = "vocal_range_low_midi")
    private Integer vocalRangeLowMidi;

    @Column(name = "vocal_range_high_midi")
    private Integer vocalRangeHighMidi;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 이메일 회원가입(LOCAL) 계정을 생성한다.
     *
     * @param email              소문자 정규화된 이메일. null/blank 금지.
     * @param passwordHash       {@code PasswordHasher} 가 인코딩한 해시 문자열. null/blank 금지.
     * @param gender             선택 — null 이면 {@link UserGender#UNSPECIFIED} 로 보정.
     * @param vocalRangeLowMidi  음역 최저음 MIDI. {@code vocalRangeHighMidi} 와 동시 null 또는 동시 present.
     * @param vocalRangeHighMidi 음역 최고음 MIDI.
     */
    public static User createLocal(
            final String email,
            final String passwordHash,
            final UserGender gender,
            final Integer vocalRangeLowMidi,
            final Integer vocalRangeHighMidi) {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        if (email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash must not be blank");
        }
        if (email.length() > EMAIL_MAX_LENGTH) {
            throw new IllegalArgumentException("email length exceeds " + EMAIL_MAX_LENGTH + ": " + email.length());
        }
        validateVocalRange(vocalRangeLowMidi, vocalRangeHighMidi);
        final LocalDateTime now = LocalDateTime.now();
        return new User(
                null,
                email,
                passwordHash,
                AuthProvider.LOCAL,
                null,
                gender == null ? UserGender.UNSPECIFIED : gender,
                vocalRangeLowMidi,
                vocalRangeHighMidi,
                now,
                now);
    }

    /**
     * 음역대/성별 프로필을 갱신한다. 인증·식별 필드(email/passwordHash/provider)는 본 메서드로 바꾸지
     * 않는다.
     */
    public void updateProfile(
            final UserGender gender,
            final Integer vocalRangeLowMidi,
            final Integer vocalRangeHighMidi) {
        validateVocalRange(vocalRangeLowMidi, vocalRangeHighMidi);
        this.gender = gender == null ? UserGender.UNSPECIFIED : gender;
        this.vocalRangeLowMidi = vocalRangeLowMidi;
        this.vocalRangeHighMidi = vocalRangeHighMidi;
        this.updatedAt = LocalDateTime.now();
    }

    private static void validateVocalRange(final Integer vocalRangeLowMidi, final Integer vocalRangeHighMidi) {
        if (vocalRangeLowMidi == null && vocalRangeHighMidi == null) {
            return;
        }
        if (vocalRangeLowMidi == null || vocalRangeHighMidi == null) {
            throw new IllegalArgumentException(
                    "vocalRangeLowMidi and vocalRangeHighMidi must be both present or both absent");
        }
        validateMidiBound("vocalRangeLowMidi", vocalRangeLowMidi);
        validateMidiBound("vocalRangeHighMidi", vocalRangeHighMidi);
        if (vocalRangeLowMidi > vocalRangeHighMidi) {
            throw new IllegalArgumentException(
                    "vocalRangeLowMidi (" + vocalRangeLowMidi + ") must be <= vocalRangeHighMidi ("
                            + vocalRangeHighMidi + ")");
        }
    }

    private static void validateMidiBound(final String fieldName, final int midi) {
        if (midi < MidiRange.LOWEST_ALLOWED_MIDI || midi > MidiRange.HIGHEST_ALLOWED_MIDI) {
            throw new IllegalArgumentException(
                    fieldName + " out of allowed range [" + MidiRange.LOWEST_ALLOWED_MIDI + ", "
                            + MidiRange.HIGHEST_ALLOWED_MIDI + "]: " + midi);
        }
    }
}
