package com.sunz.hidden_travel.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 추천 코스 (TourAPI 여행코스). 경유지는 CoursePoint 값 객체 컬렉션으로 보관.
 */
@Entity
@Table(name = "travel_course", indexes = @Index(name = "idx_course_sig", columnList = "sig_cd"))
@Getter
@Setter
@NoArgsConstructor
public class TravelCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sig_cd", length = 5, nullable = false)
    private String sigCd;

    private String title;

    /** TourAPI detailCommon2.overview — 코스 전체 소개 */
    @Column(columnDefinition = "TEXT")
    private String description;

    private String theme;

    private String totalDistance;

    /** TourAPI contentId (중복 적재 방지 키) */
    private String sourceContentId;

    @ElementCollection
    @CollectionTable(name = "course_point", joinColumns = @JoinColumn(name = "course_id"))
    @OrderColumn(name = "point_index")
    private List<CoursePoint> points = new ArrayList<>();
}
