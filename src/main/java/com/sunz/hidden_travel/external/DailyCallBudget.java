package com.sunz.hidden_travel.external;

import com.sunz.hidden_travel.domain.ApiCallUsage;
import com.sunz.hidden_travel.repository.ApiCallUsageRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 외부 API 의 1일 호출 예산. 사용량을 DB 에 기록해 <b>재시작해도 유지</b>된다.
 *
 * 서비스 이름으로 구분하므로 TourAPI·길찾기처럼 한도가 따로인 API 를 각각 셀 수 있다.
 */
@Component
public class DailyCallBudget {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ApiCallUsageRepository repository;

    public DailyCallBudget(ApiCallUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * 호출 1회를 예약한다. 한도를 넘으면 false — 호출하지 않는다.
     *
     * REQUIRES_NEW: 호출은 실제로 나갔는데 바깥 트랜잭션이 롤백되면 사용량이
     * 되돌아가 한도를 초과하게 되므로, 별도 트랜잭션으로 확정한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized boolean reserve(String service, int dailyLimit) {
        ApiCallUsage usage = today(service);
        if (usage.getCount() >= dailyLimit) {
            return false;
        }
        usage.setCount(usage.getCount() + 1);
        repository.save(usage);
        return true;
    }

    /**
     * 오늘 사용량.
     *
     * <p><b>REQUIRES_NEW + 스칼라 조회</b>여야 한다. 바깥 트랜잭션에 참여해 엔티티로 읽으면
     * 1차 캐시에 남은 옛 값이 돌아온다. {@link #reserve}가 별도 트랜잭션에서 올린 값을
     * 못 보게 되어, <b>한 요청 안에서 반복 호출하는 배치에서 한도 가드가 통째로 무력화</b>된다.
     * (실제로 예산이 0인데도 계속 호출을 시도한 사고가 있었다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public int used(String service) {
        String key = ApiCallUsage.keyOf(service, LocalDate.now(KST));
        return repository.findCountByKey(key).orElse(0);
    }

    public int remaining(String service, int dailyLimit) {
        return Math.max(0, dailyLimit - used(service));
    }

    private ApiCallUsage today(String service) {
        String key = ApiCallUsage.keyOf(service, LocalDate.now(KST));
        return repository.findById(key).orElseGet(() -> new ApiCallUsage(key, 0));
    }
}
