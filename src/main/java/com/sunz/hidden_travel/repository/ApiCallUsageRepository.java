package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.ApiCallUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ApiCallUsageRepository extends JpaRepository<ApiCallUsage, String> {

    /**
     * 오늘 사용량을 <b>스칼라로</b> 읽는다.
     *
     * {@code findById} 로 읽으면 영속성 컨텍스트(1차 캐시)에 남아 있는 엔티티가 돌아와
     * 다른 트랜잭션이 올려둔 값을 못 본다. 한 요청 안에서 여러 번 호출하는 배치에서는
     * 사용량이 <b>계속 예전 값으로 보여 한도 가드가 무력화</b>된다.
     * 스칼라 조회는 캐시를 거치지 않고 DB 를 읽는다.
     */
    @Query("select u.count from ApiCallUsage u where u.key = :key")
    Optional<Integer> findCountByKey(String key);
}
