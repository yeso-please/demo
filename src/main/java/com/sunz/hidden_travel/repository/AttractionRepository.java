package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.Attraction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttractionRepository extends JpaRepository<Attraction, Long> {

    List<Attraction> findBySigCd(String sigCd);

    boolean existsBySourceContentId(String sourceContentId);

    /** TourAPI contentId 로 조회 — 여행코스 경유지를 관광지와 연결할 때 쓴다 */
    Optional<Attraction> findFirstBySourceContentId(String sourceContentId);

    /** 관광지가 적재된 시군구 코드 목록(추천 후보 = 실제 보여줄 데이터가 있는 지역) */
    @Query("select distinct a.sigCd from Attraction a")
    List<String> findDistinctSigCd();

    /** 시군구별 관광지 수 — 챗봇 카탈로그용 (row: [sigCd, count]) */
    @Query("select a.sigCd, count(a) from Attraction a group by a.sigCd")
    List<Object[]> countBySigCd();

    /**
     * '숨은 여행지' 후보 시군구 — 광역시·경기·제주를 뺀 지역 중
     * 보여줄 관광지가 일정 수 이상 쌓인 곳.
     * (인기도 지표가 없어 "덜 알려진 곳"을 이렇게 근사한다)
     */
    @Query("""
            select a.sigCd from Attraction a
            where substring(a.sigCd, 1, 2) not in :excludedSido
            group by a.sigCd
            having count(a) >= :minCount
            """)
    List<String> findHiddenCandidateSigCds(List<String> excludedSido, long minCount);

    /** 이미지가 있는 관광지 (스포트라이트 카드 사진용) */
    List<Attraction> findBySigCdAndImageIsNotNull(String sigCd);

    /* =========================================================
       상세 설명 선적재 배치 (AttractionDetailBackfillService)
       ========================================================= */

    /**
     * 선적재 배치가 상세를 채울 유형.
     *
     * 숙박·쇼핑·축제는 소개글이 여행 판단에 큰 도움이 안 되는데
     * 건수는 많아 <b>하루 425건뿐인 배치 예산을 관광지에서 빼앗는다.</b>
     * 그래서 배치 대상에서 제외한다(필요해지면 여기에 추가하면 된다).
     */
    List<String> DETAIL_TARGET_TYPES = List.of("관광지", "문화시설", "레포츠");

    /**
     * 아직 상세를 안 받아온 관광지 id 목록.
     *
     * contentId 가 없으면 TourAPI 에서 가져올 게 없으므로 제외한다
     * (넣어두면 배치가 매번 같은 행을 집어 예산만 태운다).
     * 엔티티가 아니라 id 만 읽어 큰 목록을 메모리에 올리지 않는다.
     *
     * <p><b>지역 라운드로빈</b>으로 집는다 — 지역마다 1번째, 그다음 지역마다 2번째… 순.
     * id 순으로 집으면 앞쪽 몇 지역만 100% 차고 나머지 지역은 몇 주 동안 설명이 비어 있다.
     * 하루 코스는 지역당 네 자리뿐이라, 전 지역에 네 건씩 먼저 채우는 편이
     * 화면에 보이는 빈칸을 훨씬 빨리 없앤다(전량 완료는 어느 순서로 돌든 같은 날 끝난다).
     *
     * <p>같은 지역 안에서는 <b>사진이 있는 것</b>을 먼저 — 사진이 있어야 카드에 오르고,
     * 카드에 오르는 곳의 설명이 먼저 필요하다.
     */
    @Query(value = """
            select id from (
              select a.id as id,
                     row_number() over (
                       partition by a.sig_cd
                       order by case when a.image is null or a.image = '' then 1 else 0 end, a.id
                     ) as rn
              from attraction a
              where a.detail_fetched = false
                and a.source_content_id is not null and a.source_content_id <> ''
                and (a.type is null or a.type in (:types))
            )
            order by rn, id
            """, nativeQuery = true)
    List<Long> findDetailBackfillCandidates(@Param("types") List<String> types, Pageable pageable);

    /** 남은 선적재 대상 수 (진행률 표시용) */
    @Query("""
            select count(a) from Attraction a
            where a.detailFetched = false
              and a.sourceContentId is not null and a.sourceContentId <> ''
              and (a.type is null or a.type in :types)
            """)
    long countDetailBackfillRemaining(@Param("types") List<String> types);

    /** 설명을 실제로 확보한 관광지 수 — detailFetched 와 다르다(응답이 비어 있을 수 있다) */
    @Query("select count(a) from Attraction a where a.description is not null and a.description <> ''")
    long countWithDescription();
}
