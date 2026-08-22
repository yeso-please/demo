package com.sunz.hidden_travel.controller.dto;

import java.util.List;

/**
 * '이 지역에서 하루 보내기' — 시군구 하나를 오전·점심·오후·저녁 네 자리로 조립한 하루.
 *
 * <p>지역을 발견한 뒤 "그래서 여기서 뭘 하지?"에서 멈추지 않게 하는 것이 목적이다.
 * 지도에서 처음 본 지역이라도 누르는 즉시 실제로 움직일 수 있는 코스가 나와야 한다.
 *
 * <p><b>추정값과 실측값을 섞지 않는다.</b> 이동 거리·시간은 좌표 직선거리 기반 <b>추정</b>이고
 * (길찾기 API 는 한도가 있어 코스를 저장할 때 1회만 부른다 — DATA §7),
 * 가격은 착한가격업소 공시가라는 <b>실측</b>이다. 화면에서 이 둘을 구분해 표기한다.
 *
 * <p>운영시간은 관광지 상세(detailCommon2)에만 있고 현재 커버리지가 낮다(DATA §3).
 * 그래서 "검증됨"을 단정하지 않고 {@link #hoursVerifiedCount}/{@link #hoursCheckableCount} 로
 * <b>확인된 비율을 그대로 드러낸다.</b>
 */
public record DayPlan(
        String sigCd,
        String regionName,
        String province,
        String title,
        /** 같은 지역에서 다른 조합을 뽑기 위한 회차. 같은 값이면 항상 같은 코스가 나온다 */
        int variant,
        boolean available,
        /** available=false 일 때 화면에 그대로 보여줄 사유 */
        String unavailableReason,
        List<Stop> stops,
        /** 이 지역을 고를 만한 이유 — 전부 적재된 데이터에서 계산한 사실이다(지어내지 않는다) */
        List<String> reasons,
        String distanceText,
        String durationText,
        /** 착한가격업소 공시가로 계산한 식비. 가격 정보가 없으면 null */
        String costText,
        /**
         * 좌표가 없어 이동 추정·지도에서 빠진 장소 이름.
         * 착한가격업소가 여기 해당한다(원본에 위경도가 없다) — 숨기지 않고 화면에 밝힌다.
         */
        List<String> noCoordNames,
        int hoursVerifiedCount,
        int hoursCheckableCount
) {

    /**
     * 하루의 한 자리.
     *
     * <p>dataType 은 코스 편집기(course.js)가 쓰는 값과 같다(attraction/food/goodprice).
     * 저장 시 착한가격업소 수 집계가 이 값으로 이뤄지므로 슬롯 라벨과 분리해 둔다.
     */
    public record Stop(
            int order,
            /** 오전 · 점심 · 오후 · 저녁 */
            String slot,
            String name,
            String dataType,
            String category,
            boolean sage,
            Long attractionId,
            String image,
            String addr,
            Double lat,
            Double lng,
            /** 착한가격업소 메뉴·가격 (그 외 null) */
            String priceText,
            /** 관광지 이용시간 — 상세를 이미 받아둔 곳만 값이 있다 */
            String hoursText,
            boolean hoursVerified
    ) {}

    public boolean hasStops() {
        return stops != null && !stops.isEmpty();
    }

    public boolean hasReasons() {
        return reasons != null && !reasons.isEmpty();
    }

    public boolean hasNoCoordStops() {
        return noCoordNames != null && !noCoordNames.isEmpty();
    }

    /** 운영시간을 확인할 수 있는 자리가 있는데 아직 다 못 채운 상태 — 화면이 이걸 숨기지 않는다 */
    public boolean hasHoursGap() {
        return hoursCheckableCount > 0 && hoursVerifiedCount < hoursCheckableCount;
    }
}
