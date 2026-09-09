package com.sunz.hidden_travel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 서비스 사용자. 코스/후기의 작성자이자 로그인 주체.
 *
 * 로그인은 이메일 + 비밀번호(BCrypt 해시) 방식.
 * 소셜 로그인으로 확장할 때는 provider/providerId 를 추가하고
 * password 를 nullable 로 완화하면 된다.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 아이디 (중복 불가) */
    @Column(nullable = false, unique = true, length = 190)
    private String email;

    /** BCrypt 해시 — 평문을 저장하지 않는다 */
    @Column(nullable = false)
    private String password;

    /** 화면에 노출되는 이름 */
    @Column(nullable = false, length = 30)
    private String nickname;

    /** 프로필 한 줄 소개 (선택) */
    @Column(length = 200)
    private String bio;

    /** 프로필 사진 경로 (선택, 예: /uploads/profiles/xxx.jpg) */
    @Column(name = "profile_image")
    private String profileImage;

    /**
     * 여행 MBTI 4글자 (예: "ENFP"). 온보딩 검사로 정해지며 다시 할 수 있다.
     * 검사 전이면 null — 프로필·후기에서 뱃지를 숨긴다.
     */
    @Column(name = "travel_mbti", length = 4)
    private String travelMbti;

    /**
     * 온보딩에서 고른 경험 태그 (쉼표로 이어 붙임, 최대 5개).
     * 유형(MBTI)이 '사람의 결'이라면 이쪽은 '장소를 고르는 값'이다 —
     * 코스를 조립할 때 관광지 태그와 맞춰 본다({@link com.sunz.hidden_travel.service.ExperienceTags}).
     */
    @Column(name = "experience_tags", length = 200)
    private String experienceTags;

    /** 명시적 제외 조건 (쉼표로 이어 붙임). 자동으로 풀지 않는다 — PRD 6.2 */
    @Column(name = "exclude_tags", length = 200)
    private String excludeTags;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public AppUser(String email, String password, String nickname) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
    }

    /** 프로필 사진이 없으면 화면에서 이니셜 아바타를 쓴다 */
    public String initial() {
        return (nickname == null || nickname.isBlank()) ? "?" : nickname.substring(0, 1);
    }

    /** 여행 MBTI 유형 (검사 전이면 null) */
    public com.sunz.hidden_travel.mbti.TravelMbtiType mbtiType() {
        return com.sunz.hidden_travel.mbti.TravelMbtiType.of(travelMbti);
    }

    /** 경험 태그 목록 (고른 적 없으면 빈 목록) */
    public java.util.List<String> experienceTagList() {
        return splitCsv(experienceTags);
    }

    /** 제외 조건 목록 (고른 적 없으면 빈 목록) */
    public java.util.List<String> excludeTagList() {
        return splitCsv(excludeTags);
    }

    private static java.util.List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
