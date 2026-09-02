package com.sunz.hidden_travel.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 여행 다이어리 한 편.
 *
 * <p><b>이 서비스의 입력이자 출력이다.</b> 온보딩에서 쓰는 '기억'과 여행을 다녀와서 쓰는 '기록'이
 * 같은 객체다. 둘을 나누면 후기는 취향으로 안 읽히고, 다이어리는 사진도 공유도 없는
 * 반쪽짜리 둘이 된다 — 실제로 그렇게 나뉘어 있었다({@code Review} 참고).
 *
 * <p>{@link #savedCourseId} 가 있으면 <b>우리가 추천한 코스로 다녀와서 쓴 글</b>이다.
 * 추천이 맞았는지 알 수 있는 유일한 증거라서 따로 둔다.
 *
 * <p>비로그인 사용자의 글은 여기 오지 않고 세션에 있다 — 첫 가치 경험을 로그인 뒤로
 * 미루지 않기 위해서다. 로그인하는 순간 세션에 있던 편들이 이리로 옮겨진다.
 */
@Entity
@Table(name = "diary", indexes = {
        @Index(name = "idx_diary_user", columnList = "user_id"),
        @Index(name = "idx_diary_sig", columnList = "sig_cd")
})
public class Diary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "sig_cd", length = 5, nullable = false)
    private String sigCd;

    /** 사용자가 쓴 줄글. 여기서 경험 태그를 읽는다 */
    @Column(columnDefinition = "TEXT")
    private String text;

    /** 글에서 읽은 경험 태그 — 사용자가 뺀 것은 제외된 최종본 */
    @Column(name = "tags_csv", length = 300)
    private String tagsCsv;

    /** again · good · soso · bad */
    @Column(length = 10)
    private String satisfaction;

    /** "작년 가을" 처럼 사용자가 쓴 그대로. 날짜로 강제하지 않는다 */
    @Column(name = "when_text", length = 60)
    private String whenText;

    /**
     * 다녀온 코스. 비어 있으면 '기억'(과거 여행)이고,
     * 값이 있으면 '기록'(우리 추천으로 실제 다녀온 여행)이다.
     */
    @Column(name = "saved_course_id")
    private Long savedCourseId;

    @Column(nullable = false)
    private boolean shared = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "diary_photo", joinColumns = @JoinColumn(name = "diary_id"))
    @Column(name = "path")
    private List<String> photoPaths = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 이 편이 '다녀와서 쓴 기록'인지 */
    public boolean fromCourse() {
        return savedCourseId != null;
    }

    public List<String> tags() {
        if (tagsCsv == null || tagsCsv.isBlank()) return List.of();
        return Arrays.stream(tagsCsv.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    public void setTags(List<String> tags) {
        this.tagsCsv = tags == null || tags.isEmpty() ? null : String.join(",", tags);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSigCd() { return sigCd; }
    public void setSigCd(String sigCd) { this.sigCd = sigCd; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getTagsCsv() { return tagsCsv; }
    public void setTagsCsv(String tagsCsv) { this.tagsCsv = tagsCsv; }
    public String getSatisfaction() { return satisfaction; }
    public void setSatisfaction(String satisfaction) { this.satisfaction = satisfaction; }
    public String getWhenText() { return whenText; }
    public void setWhenText(String whenText) { this.whenText = whenText; }
    public Long getSavedCourseId() { return savedCourseId; }
    public void setSavedCourseId(Long savedCourseId) { this.savedCourseId = savedCourseId; }
    public boolean isShared() { return shared; }
    public void setShared(boolean shared) { this.shared = shared; }
    public List<String> getPhotoPaths() { return photoPaths; }
    public void setPhotoPaths(List<String> photoPaths) { this.photoPaths = photoPaths; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
