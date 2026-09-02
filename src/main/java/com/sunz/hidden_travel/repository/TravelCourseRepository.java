package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.TravelCourse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TravelCourseRepository extends JpaRepository<TravelCourse, Long> {

    List<TravelCourse> findBySigCd(String sigCd);

    /** 추천 후보를 만들 때 경유지·사진까지 한 번에 읽어 N+1 조회를 피한다. */
    @Query("select distinct c from TravelCourse c left join fetch c.points")
    List<TravelCourse> findAllWithPoints();

    boolean existsBySourceContentId(String sourceContentId);
}
