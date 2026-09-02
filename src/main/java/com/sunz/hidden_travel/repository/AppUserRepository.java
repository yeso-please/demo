package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** 로그인·중복가입 확인용 */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 닉네임 중복 확인 (표시 이름이라 유일성을 강제하진 않지만 안내에 쓴다) */
    boolean existsByNickname(String nickname);

    /** 공개 지도(/u/{닉네임})가 사람을 찾는 방법 */
    Optional<AppUser> findByNickname(String nickname);
}
