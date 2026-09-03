package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.controller.dto.RegionBundle;
import com.sunz.hidden_travel.controller.dto.RegionPreview;
import com.sunz.hidden_travel.service.RegionQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지역 정보 JSON API.
 * 지도에서 시군구를 클릭하면 SIG_CD 로 이 엔드포인트를 호출해 패널을 채운다.
 * DB 실데이터(Region + Attraction/FoodPlace/GoodPriceShop/Specialty/TravelCourse)를 조립해 반환.
 */
@RestController
@RequestMapping("/api/regions")
public class RegionController {

    private final RegionQueryService regionQueryService;

    public RegionController(RegionQueryService regionQueryService) {
        this.regionQueryService = regionQueryService;
    }

    /** GET /api/regions/{sigCd} → 해당 지역 실데이터 번들 (JSON) */
    @GetMapping("/{sigCd}")
    public RegionBundle region(@PathVariable String sigCd) {
        return regionQueryService.bundle(sigCd);
    }

    /** GET /api/regions/{sigCd}/preview → 지도 선택 직후의 작은 지역 카드 */
    @GetMapping("/{sigCd}/preview")
    public RegionPreview preview(@PathVariable String sigCd) {
        return regionQueryService.preview(sigCd);
    }
}
