package com.mobruji.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    @DisplayName("createLocal 은 LOCAL provider + 프로필을 세팅하고 null gender 는 UNSPECIFIED 로 보정한다")
    void createLocal_setsLocalProviderAndProfile() {
        // when
        final User user = User.createLocal("singer@example.com", "hash", null, 48, 72);

        // then
        assertThat(user.getEmail()).isEqualTo("singer@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("hash");
        assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(user.getProviderUserId()).isNull();
        assertThat(user.getGender()).isEqualTo(UserGender.UNSPECIFIED);
        assertThat(user.getVocalRangeLowMidi()).isEqualTo(48);
        assertThat(user.getVocalRangeHighMidi()).isEqualTo(72);
    }

    @Test
    @DisplayName("createLocal 은 음역대 없이도 생성된다")
    void createLocal_allowsAbsentVocalRange() {
        final User user = User.createLocal("singer@example.com", "hash", UserGender.FEMALE, null, null);

        assertThat(user.getVocalRangeLowMidi()).isNull();
        assertThat(user.getVocalRangeHighMidi()).isNull();
        assertThat(user.getGender()).isEqualTo(UserGender.FEMALE);
    }

    @Test
    @DisplayName("createLocal 은 음역대 한쪽만 있으면 거부한다")
    void createLocal_rejectsPartialVocalRange() {
        assertThatThrownBy(() -> User.createLocal("singer@example.com", "hash", null, 48, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("createLocal 은 최저음 > 최고음 이면 거부한다")
    void createLocal_rejectsInvertedRange() {
        assertThatThrownBy(() -> User.createLocal("singer@example.com", "hash", null, 80, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("createLocal 은 허용 범위를 벗어난 MIDI 를 거부한다")
    void createLocal_rejectsOutOfRangeMidi() {
        assertThatThrownBy(() -> User.createLocal("singer@example.com", "hash", null, 5, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("updateProfile 은 성별/음역대를 갱신한다")
    void updateProfile_updatesFields() {
        // given
        final User user = User.createLocal("singer@example.com", "hash", UserGender.UNSPECIFIED, null, null);

        // when
        user.updateProfile(UserGender.MALE, 41, 65);

        // then
        assertThat(user.getGender()).isEqualTo(UserGender.MALE);
        assertThat(user.getVocalRangeLowMidi()).isEqualTo(41);
        assertThat(user.getVocalRangeHighMidi()).isEqualTo(65);
    }
}
