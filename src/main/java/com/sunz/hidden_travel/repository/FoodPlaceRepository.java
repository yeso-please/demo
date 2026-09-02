package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.FoodPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FoodPlaceRepository extends JpaRepository<FoodPlace, Long> {

    List<FoodPlace> findBySigCd(String sigCd);

    boolean existsBySourceContentId(String sourceContentId);

    java.util.Optional<FoodPlace> findFirstBySourceContentId(String sourceContentId);

    @Query("select f.sigCd, count(f) from FoodPlace f group by f.sigCd")
    List<Object[]> countBySigCd();
}
