package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.GoodPriceShop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GoodPriceShopRepository extends JpaRepository<GoodPriceShop, Long> {

    List<GoodPriceShop> findBySigCd(String sigCd);

    boolean existsByNameAndAddr(String name, String addr);

    @Query("select g.sigCd, count(g) from GoodPriceShop g group by g.sigCd")
    List<Object[]> countBySigCd();
}
