package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.controller.dto.DiscoveryCandidate;
import com.sunz.hidden_travel.controller.dto.TravelerDna;
import com.sunz.hidden_travel.controller.dto.VisitInput;
import com.sunz.hidden_travel.domain.Attraction;
import com.sunz.hidden_travel.domain.CoursePoint;
import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.TravelCourse;
import com.sunz.hidden_travel.repository.AttractionRepository;
import com.sunz.hidden_travel.repository.FoodPlaceRepository;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.repository.TravelCourseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 취향 기반 발견의 베이스라인.
 *
 * <p><b>무엇을 하는가</b>
 * <ol>
 *   <li>지역마다 '경험 벡터'를 만든다 — 그 지역 관광지 텍스트에서 {@link ExperienceTags} 를 세고 정규화</li>
 *   <li>사용자가 좋았다고 고른 지역들의 벡터를 만족도로 가중 평균해 '여행 DNA' 를 만든다</li>
 *   <li>아직 안 가본 지역 중 DNA 와 방향이 비슷한 곳을 찾는다</li>
 * </ol>
 *
 * <p><b>왜 정규화가 중요한가</b>
 * 관광지 개수를 그대로 쓰면 서울·부산처럼 콘텐츠가 많은 지역이 항상 이긴다.
 * 그건 취향이 맞아서가 아니라 데이터가 많아서다 — 지금 추천이 가진 문제와 같다.
 * 그래서 벡터를 <b>길이 1로 정규화</b>해 "무엇이 많은가"가 아니라 "무엇에 치우쳐 있는가"만 비교한다.
 *
 * <p><b>노출 편향 처리</b>
 * 유사도가 충분한 후보를 먼저 모으고(임계값), 그 안에서 다양성을 보고 고른다.
 * <b>사용자에게 점수·순위를 주지 않는다.</b> 점수로 줄을 세우면 인기 랭킹을 다른 이름으로 만드는 것과 같다.
 *
 * <p><b>한계</b> — 학습이 없다. 문자열 사전 기반이라 표현력이 얕고,
 * 관광지 상세 설명 확보율에 품질이 좌우된다. 학습 모델을 붙일 때 이 클래스가 baseline 이 된다.
 */
@Service
public class TravelerProfileService {

    /**
     * 후보로 인정할 최소 유사도(코사인) — <b>절대 바닥값</b>.
     *
     * <p>고정 임계값 하나로는 안 된다. 다이어리를 한 편 쓴 사람의 DNA 는 태그 서너 개짜리
     * 성긴 벡터이고, 여러 편 쓴 사람의 DNA 는 촘촘하다. 지역 벡터는 12축이 대체로 차 있어
     * 성긴 DNA 와의 코사인은 구조적으로 낮게 나온다 — 실측: 한 편(골목·바다·카페·시장)으로는
     * 0.80 을 넘는 지역이 <b>250곳 중 0곳</b>이었다. 잘 쓴 글일수록 결과가 비는 셈이다.
     *
     * <p>그래서 "얼마나 닮았나"를 절대값으로 묻지 않고 {@link #RELATIVE_BAND} 로
     * <b>그 사람 기준 가장 닮은 곳에 얼마나 근접한가</b>를 묻는다.
     * 이 값은 그 아래로는 무엇에도 닮지 않았다고 볼 바닥일 뿐이다.
     */
    private static final double MIN_SIMILARITY = 0.45;

    /**
     * 가장 닮은 곳 대비 이만큼 안쪽이면 후보로 본다.
     *
     * <p>1.0 이면 최상위 한 곳만, 0 이면 전국이 통과한다. 0.92 는 화면에 놓을
     * 서너 장을 남기면서 결이 다른 곳은 떨어지는 지점이다 — 표현이 학습 기반으로 바뀌면
     * 이 값도 다시 잡아야 한다.
     */
    private static final double RELATIVE_BAND = 0.92;

    /**
     * 근교 반나절용 임계값.
     * 근교는 선택지가 애초에 적어서 하루 코스와 같은 0.80 을 대면 후보가 0 이 되는 지역이 많다.
     * '지금 나갈까'에서는 완벽한 취향 일치보다 갈 수 있는지가 먼저다.
     */
    private static final double NEARBY_MIN_SIMILARITY = 0.62;

    /** 코스를 만들 수 있을 만큼의 최소 데이터 — 발견해도 갈 수 없으면 의미가 없다 */
    private static final int MIN_ATTRACTIONS = 3;

    /** 만족도 → 가중치. '안 맞았어요'는 음수로 두어 그 방향을 피하게 한다. */
    private static final Map<String, Double> SATISFACTION_WEIGHT = Map.of(
            "again", 1.0,      // 다시 가고 싶어요
            "good", 0.7,       // 좋았어요
            "soso", 0.2,       // 보통
            "bad", -0.5        // 안 맞았어요
    );

    private final RegionRepository regionRepository;
    private final AttractionRepository attractionRepository;
    private final FoodPlaceRepository foodPlaceRepository;
    private final TravelCourseRepository travelCourseRepository;

    /** 지역 경험 벡터 캐시 — 관광지 텍스트는 배치로만 바뀌므로 요청마다 다시 계산할 이유가 없다 */
    private final Map<String, Map<String, Double>> vectorCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> rawCountCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> attractionCountCache = new ConcurrentHashMap<>();
    /** 지역 → 대표 관광지. 카드마다 DB 를 다시 훑지 않기 위한 캐시(사진 없는 지역은 null 저장 불가라 별도 처리). */
    private final Map<String, Attraction> heroCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<Map<String, Double>> idfCache =
            new java.util.concurrent.atomic.AtomicReference<>();

    public TravelerProfileService(RegionRepository regionRepository,
                                  AttractionRepository attractionRepository,
                                  FoodPlaceRepository foodPlaceRepository,
                                  TravelCourseRepository travelCourseRepository) {
        this.regionRepository = regionRepository;
        this.attractionRepository = attractionRepository;
        this.foodPlaceRepository = foodPlaceRepository;
        this.travelCourseRepository = travelCourseRepository;
    }

    /* =========================================================
       지역 경험 벡터
       ========================================================= */

    @Transactional(readOnly = true)
    public Map<String, Double> regionVector(String sigCd) {
        Map<String, Double> cached = vectorCache.get(sigCd);
        if (cached != null) {
            return cached;
        }
        Map<String, Double> idf = idf();
        Map<String, Integer> counts = rawCounts(sigCd);

        Map<String, Double> weighted = new LinkedHashMap<>();
        counts.forEach((tag, n) -> {
            double w = n * idf.getOrDefault(tag, 1.0);
            if (w > 0) weighted.put(tag, w);
        });

        Map<String, Double> vector = normalize(weighted);
        vectorCache.put(sigCd, vector);
        return vector;
    }

    /** 태그 원시 등장 횟수 (IDF 계산과 벡터 생성이 함께 쓴다) */
    private Map<String, Integer> rawCounts(String sigCd) {
        Map<String, Integer> cached = rawCountCache.get(sigCd);
        if (cached != null) {
            return cached;
        }
        List<Attraction> attractions = attractionRepository.findBySigCd(sigCd);
        attractionCountCache.put(sigCd, attractions.size());

        StringBuilder text = new StringBuilder();
        for (Attraction a : attractions) {
            text.append(' ').append(ExperienceTags.lower(a.getName()))
                .append(' ').append(ExperienceTags.lower(a.getDescription()))
                .append(' ').append(ExperienceTags.lower(a.getType()));
        }
        Map<String, Integer> counts = ExperienceTags.countByTag(text.toString());
        rawCountCache.put(sigCd, counts);
        return counts;
    }

    /**
     * 태그별 IDF 가중치.
     *
     * <p><b>왜 필요한가</b> — '산'·'길'·'시장' 같은 단어는 전국 거의 모든 지역에 등장한다.
     * 그대로 세면 모든 지역 벡터가 비슷해지고, 결국 어떤 지역을 추천해도 근거 문장이 똑같아진다
     * (실측: 후보 3곳의 일치 태그가 전부 동일했다).
     * 흔한 축의 가중치를 낮추면 <b>그 지역에서 유난한 축</b>이 드러난다.
     *
     * <p>지역 수가 250개로 고정이라 한 번 계산해 캐시한다.
     */
    private Map<String, Double> idf() {
        Map<String, Double> cached = idfCache.get();
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = idfCache.get();
            if (cached != null) return cached;

            List<Region> regions = regionRepository.findAll();
            int n = Math.max(1, regions.size());
            Map<String, Integer> docFreq = new LinkedHashMap<>();
            for (Region r : regions) {
                for (String tag : rawCounts(r.getSigCd()).keySet()) {
                    docFreq.merge(tag, 1, Integer::sum);
                }
            }
            Map<String, Double> out = new LinkedHashMap<>();
            for (String tag : ExperienceTags.all()) {
                int df = docFreq.getOrDefault(tag, 0);
                // +1 스무딩 — df=0(전국에 한 번도 안 나온 태그)에서 나눗셈이 깨지지 않게
                out.put(tag, Math.log((double) (n + 1) / (df + 1)) + 1.0);
            }
            idfCache.set(out);
            return out;
        }
    }

    /** 배치로 데이터가 바뀐 뒤 캐시를 비운다 */
    public void clearCache() {
        vectorCache.clear();
        rawCountCache.clear();
        attractionCountCache.clear();
        idfCache.set(null);
    }

    /* =========================================================
       여행 DNA
       ========================================================= */

    /**
     * 방문 이력에서 여행 DNA 를 만든다.
     *
     * @param visits 사용자가 고른 지역 + 만족도 + (선택) 직접 고른 경험 태그
     */
    @Transactional(readOnly = true)
    public TravelerDna buildDna(List<VisitInput> visits) {
        Map<String, Double> acc = new HashMap<>();
        List<String> basis = new ArrayList<>();

        for (VisitInput v : visits) {
            if (v == null || v.sigCd() == null || v.sigCd().isBlank()) continue;
            double w = SATISFACTION_WEIGHT.getOrDefault(v.satisfaction(), 0.5);
            if (w == 0) continue;

            // 1) 지역이 실제로 가진 경험 — 배경 신호
            regionVector(v.sigCd()).forEach((tag, val) -> acc.merge(tag, val * w, Double::sum));

            // 2) 사용자가 쓴 글에서 읽은 경험 — 본인 진술이라 훨씬 크게 본다.
            //    같은 태그가 글에 여러 번 나오면 그만큼 더 센다(한 번 스친 것과 계속 쓴 것은 다르다).
            List<String> written = v.tags() != null && !v.tags().isEmpty()
                    ? v.tags()                                  // 사용자가 틀린 것을 뺀 결과
                    : ExperienceTags.tagsFrom(v.note());        // 아직 손대지 않았으면 글에서 다시 읽는다

            Map<String, Integer> weightByTag = new LinkedHashMap<>();
            for (ExperienceTags.Span sp : ExperienceTags.spans(v.note())) {
                if (written.contains(sp.tag())) {
                    weightByTag.merge(sp.tag(), 1, Integer::sum);
                }
            }
            for (String tag : written) {
                if (!ExperienceTags.all().contains(tag)) continue;
                // 등장 횟수는 3회까지만 반영 — 한 단어를 반복해 쓴다고 취향이 그만큼 세지는 않다
                int hits = Math.min(3, weightByTag.getOrDefault(tag, 1));
                acc.merge(tag, w * 1.5 * hits, Double::sum);
            }

            if (w > 0 && !written.isEmpty()) {
                basis.add(regionName(v.sigCd()) + "에서 '" + String.join("·", written.stream().limit(3).toList()) + "'");
            }
        }

        // 음수는 0으로 — '안 맞았어요'가 다른 축을 끌어내리기만 하고 방향은 뒤집지 않게
        acc.replaceAll((k, val) -> Math.max(0, val));
        Map<String, Double> vector = normalize(acc);

        List<TravelerDna.Axis> axes = vector.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(e -> new TravelerDna.Axis(e.getKey(), round(e.getValue())))
                .toList();

        return new TravelerDna(axes, sentence(axes), basis, vector);
    }

    /* =========================================================
       발견 — 안 가본 곳 중 결이 비슷한 지역
       ========================================================= */

    /**
     * 방문한 지역을 제외하고, DNA 와 방향이 비슷한 지역을 찾는다.
     *
     * 점수 순으로 자르지 않는다. 임계값을 넘은 후보를 모은 뒤
     * <b>서로 다른 결</b>을 가진 지역이 섞이도록 고른다(같은 성격 3곳을 주면 발견이 아니다).
     */
    @Transactional(readOnly = true)
    public List<DiscoveryCandidate> discover(TravelerDna dna, Set<String> visited, int limit) {
        Map<String, Long> foodCounts = new HashMap<>();
        for (Object[] row : foodPlaceRepository.countBySigCd()) {
            foodCounts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        // 추천의 단위는 행정구역이 아니라 TourAPI 공식 코스다. 경유지가 없는 제목뿐인
        // 코스는 실제 화면을 구성할 수 없으므로 후보에 넣지 않는다.
        Map<String, List<TravelCourse>> coursesByRegion = travelCourseRepository.findAllWithPoints().stream()
                .filter(this::usableCourse)
                .collect(Collectors.groupingBy(TravelCourse::getSigCd));

        List<Scored> pool = new ArrayList<>();
        for (Region region : regionRepository.findAll()) {
            String sigCd = region.getSigCd();
            if (visited.contains(sigCd)) continue;

            Map<String, Double> vector = regionVector(sigCd);
            if (vector.isEmpty()) continue;
            if (attractionCountCache.getOrDefault(sigCd, 0) < MIN_ATTRACTIONS) continue;

            CourseChoice choice = bestCourse(dna, vector, coursesByRegion.get(sigCd));
            if (choice == null || choice.similarity() < MIN_SIMILARITY) continue;

            pool.add(new Scored(region, choice.vector(), choice.similarity(),
                    foodCounts.getOrDefault(sigCd, 0L), choice.course()));
        }
        keepNearBest(pool);

        // 결이 겹치지 않도록: 이미 고른 지역의 대표 태그는 다음 선택에서 뒤로 민다
        List<DiscoveryCandidate> out = new ArrayList<>();
        Set<String> usedTopTags = new java.util.HashSet<>();
        List<Scored> remaining = new ArrayList<>(pool);

        while (out.size() < limit && !remaining.isEmpty()) {
            Scored best = null;
            double bestKey = -1;
            for (Scored s : remaining) {
                String top = topTag(s.vector());
                double penalty = usedTopTags.contains(top) ? 0.25 : 0.0;
                double key = s.similarity - penalty;
                if (key > bestKey) {
                    bestKey = key;
                    best = s;
                }
            }
            if (best == null) break;
            remaining.remove(best);
            usedTopTags.add(topTag(best.vector()));

            List<String> tags = topTags(best.vector(), 3);
            TravelCourse course = best.course();
            String cover = courseImage(course);
            List<String> stops = courseStops(course);
            out.add(new DiscoveryCandidate(
                    best.region.getSigCd(),
                    best.region.getName(),
                    best.region.getProvince(),
                    tags,
                    matchedTags(dna, best.vector()),
                    courseReason(dna, best.vector()),
                    cover,
                    stops.isEmpty() ? null : stops.get(0),
                    attractionCountCache.getOrDefault(best.region.getSigCd(), 0),
                    (int) best.foodCount,
                    best.region.getLat(),
                    best.region.getLng(),
                    course.getId(),
                    course.getTitle(),
                    courseSubtitle(course),
                    cover,
                    stops));
        }
        return out;
    }

    /**
     * 근교 반나절용 후보 — <b>거리로 먼저 자르고 그 안에서 취향으로 정렬한다.</b>
     *
     * <p>하루 코스의 {@link #discover}는 취향 유사도로 거르고 거리는 페널티로만 쓴다.
     * 반나절은 순서가 반대다. "지금 나갈까"는 <b>얼마나 가까운가가 먼저</b>이고,
     * 취향은 그 안에서 무엇을 고를지의 문제다. 순서를 그대로 두면
     * 취향은 맞지만 두 시간 걸리는 곳이 상위에 올라와 나들이가 성립하지 않는다.
     *
     * <p>임계값도 낮춘다({@link #MIN_SIMILARITY} 대신 {@link #NEARBY_MIN_SIMILARITY}).
     * 근교는 선택지가 애초에 적어서 하루 코스와 같은 잣대를 대면 후보가 0이 된다.
     *
     * @param maxKm 직선거리 상한. 실제 이동시간이 아니므로 화면에서 '대략'임을 밝혀야 한다.
     */
    @Transactional(readOnly = true)
    public List<DiscoveryCandidate> discoverNearby(TravelerDna dna, Set<String> visited,
                                                   double originLat, double originLng,
                                                   double maxKm, int limit) {
        Map<String, Long> foodCounts = new HashMap<>();
        for (Object[] row : foodPlaceRepository.countBySigCd()) {
            foodCounts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        record Near(Region region, Map<String, Double> vector, double km, double sim, long food) {}
        List<Near> pool = new ArrayList<>();

        for (Region region : regionRepository.findAll()) {
            if (region.getLat() == null || region.getLng() == null) continue;
            if (visited.contains(region.getSigCd())) continue;

            double km = haversine(originLat, originLng, region.getLat(), region.getLng());
            if (km > maxKm) continue;                                   // ① 거리로 먼저 자른다

            if (attractionCountCache.getOrDefault(region.getSigCd(), 0) < MIN_ATTRACTIONS
                    && regionVector(region.getSigCd()).isEmpty()) continue;

            Map<String, Double> vector = regionVector(region.getSigCd());
            if (vector.isEmpty()) continue;
            if (attractionCountCache.getOrDefault(region.getSigCd(), 0) < MIN_ATTRACTIONS) continue;

            double sim = dna == null ? 0 : cosine(dna.vector(), vector);
            if (dna != null && sim < NEARBY_MIN_SIMILARITY) continue;   // ② 그 안에서 취향

            pool.add(new Near(region, vector, km, sim, foodCounts.getOrDefault(region.getSigCd(), 0L)));
        }

        // 취향이 없으면(온보딩 전) 가까운 순, 있으면 취향 순 — 둘 다 거리 상한 안이다
        pool.sort(dna == null
                ? Comparator.comparingDouble(Near::km)
                : Comparator.comparingDouble(Near::sim).reversed());

        List<DiscoveryCandidate> out = new ArrayList<>();
        for (Near n : pool) {
            if (out.size() >= limit) break;
            List<String> tags = topTags(n.vector(), 3);
            String reason = dna == null
                    ? distancePhrase(n.km())
                    : reason(dna, n.vector(), tags) + " " + distancePhrase(n.km());
            out.add(new DiscoveryCandidate(
                    n.region().getSigCd(), n.region().getName(), n.region().getProvince(),
                    tags, dna == null ? tags : matchedTags(dna, n.vector()), reason,
                    heroImage(n.region().getSigCd()), heroName(n.region().getSigCd()),
                    attractionCountCache.getOrDefault(n.region().getSigCd(), 0),
                    (int) n.food(), n.region().getLat(), n.region().getLng(),
                    null, null, null, null, List.of()));
        }
        return out;
    }

    /**
     * 지역의 대표 사진.
     *
     * <p>카드에 사진이 없으면 "가 보고 싶다"는 마음이 생기지 않는다. 그런데 사진은
     * 관광지에만 붙어 있어 지역 단위로 하나를 골라야 한다. 첫 번째를 쓰는 게 아니라
     * <b>같은 지역이면 늘 같은 사진</b>이 나오게 이름순 첫 장을 고른다 —
     * 새로고침할 때마다 사진이 바뀌면 그 지역이라는 감각이 안 생긴다.
     *
     * <p>사진이 없는 지역은 {@code null}. 화면이 사진 영역을 빼도록 그대로 내보낸다.
     */
    private Attraction hero(String sigCd) {
        return heroCache.computeIfAbsent(sigCd, cd -> attractionRepository
                .findBySigCdAndImageIsNotNull(cd).stream()
                .min(Comparator.comparing(Attraction::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null));
    }

    private String heroImage(String sigCd) {
        Attraction a = hero(sigCd);
        return a == null ? null : a.getImage();
    }

    private String heroName(String sigCd) {
        Attraction a = hero(sigCd);
        return a == null ? null : a.getName();
    }

    /**
     * 거리를 사람 말로 옮긴다.
     * 2km 를 "차로 2km"라고 하면 어색하다 — 그 거리는 걸어가는 거리다.
     * 직선거리라는 사실도 숨기지 않는다.
     */
    private static String distancePhrase(double km) {
        // 반올림한 값으로 판정한다 — 4.6km 를 "5km 남짓이라 걸어서도" 라고 쓰면 말이 어긋난다
        long r = Math.round(km);
        if (r <= 3) return "여기서 " + r + "km 남짓이라 걸어서도 갈 만해요.";
        if (r < 20) return "여기서 " + r + "km, 대중교통으로 금방이에요.";
        if (r < 50) return "차로 " + r + "km 안쪽이라 반나절이면 다녀와요.";
        return "직선으로 " + r + "km — 반나절이면 조금 빠듯할 수 있어요.";
    }

    /** 두 좌표 사이 직선거리(km) */
    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    /**
     * 후보가 전부 몇 곳인지 — "아직 안 가본 곳이 N곳 있어요" 용.
     * {@link #discover}와 <b>같은 규칙</b>으로 센다. 세는 규칙과 고르는 규칙이 다르면
     * "12곳 있어요"라고 말해 놓고 3곳도 못 보여주는 일이 생긴다.
     */
    @Transactional(readOnly = true)
    public int candidateCount(TravelerDna dna, Set<String> visited) {
        Map<String, List<TravelCourse>> coursesByRegion = travelCourseRepository.findAllWithPoints().stream()
                .filter(this::usableCourse)
                .collect(Collectors.groupingBy(TravelCourse::getSigCd));
        List<Double> sims = new ArrayList<>();
        for (Region region : regionRepository.findAll()) {
            String sigCd = region.getSigCd();
            if (visited.contains(sigCd)) continue;
            Map<String, Double> vector = regionVector(sigCd);
            if (vector.isEmpty()) continue;
            if (attractionCountCache.getOrDefault(sigCd, 0) < MIN_ATTRACTIONS) continue;
            CourseChoice choice = bestCourse(dna, vector, coursesByRegion.get(sigCd));
            if (choice != null && choice.similarity() >= MIN_SIMILARITY) sims.add(choice.similarity());
        }
        if (sims.isEmpty()) return 0;
        double cut = sims.stream().mapToDouble(Double::doubleValue).max().orElse(0) * RELATIVE_BAND;
        return (int) sims.stream().filter(v -> v >= cut).count();
    }

    /**
     * 가장 닮은 곳에서 {@link #RELATIVE_BAND} 안쪽만 남긴다.
     *
     * <p>"결이 같다"는 절대적인 양이 아니라 이 사람 기준의 비교다. 성긴 DNA 에서는
     * 최고 유사도 자체가 낮게 나오는데, 그때 고정 컷을 대면 결과가 통째로 비어 버린다.
     * 비교 대상이 없는 게 아니라 자로 잰 눈금이 안 맞았을 뿐이다.
     */
    private static void keepNearBest(List<Scored> pool) {
        double best = pool.stream().mapToDouble(s -> s.similarity).max().orElse(0);
        double cut = best * RELATIVE_BAND;
        pool.removeIf(s -> s.similarity < cut);
    }

    /* =========================================================
       내부 계산
       ========================================================= */

    private static Map<String, Double> normalize(Map<String, ? extends Number> counts) {
        double norm = 0;
        for (Number v : counts.values()) {
            norm += v.doubleValue() * v.doubleValue();
        }
        if (norm <= 0) {
            return Map.of();
        }
        double len = Math.sqrt(norm);
        Map<String, Double> out = new LinkedHashMap<>();
        counts.forEach((k, v) -> {
            double val = v.doubleValue() / len;
            if (val > 0) out.put(k, val);
        });
        return out;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        double dot = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            Double other = b.get(e.getKey());
            if (other != null) dot += e.getValue() * other;
        }
        return dot;   // 양쪽 모두 길이 1로 정규화돼 있어 내적이 곧 코사인
    }

    private static String topTag(Map<String, Double> vector) {
        return vector.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private static List<String> topTags(Map<String, Double> vector, int n) {
        return vector.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * DNA 와 지역이 함께 갖고 있는 축.
     *
     * <b>DNA 상위 축을 그대로 쓰면 안 된다.</b> 그러면 모든 카드가 같은 태그를 달고
     * 근거 문장까지 똑같아져서, 세 곳을 나란히 보여주는 의미가 사라진다.
     * 대신 <b>기여도(DNA 가중치 × 지역 가중치)</b>가 큰 축을 고른다 — 지역마다 달라진다.
     */
    private static List<String> matchedTags(TravelerDna dna, Map<String, Double> vector) {
        Map<String, Double> contribution = new LinkedHashMap<>();
        dna.vector().forEach((tag, w) -> {
            Double regionWeight = vector.get(tag);
            if (regionWeight != null) {
                contribution.put(tag, w * regionWeight);
            }
        });
        return contribution.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 이 지역에서만 유난히 두드러지는 축 — DNA 에는 약하지만 지역엔 강한 것 */
    private static String distinctive(TravelerDna dna, Map<String, Double> vector) {
        return vector.entrySet().stream()
                .filter(e -> dna.vector().getOrDefault(e.getKey(), 0.0) < 0.25)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static String reason(TravelerDna dna, Map<String, Double> vector, List<String> tags) {
        List<String> matched = matchedTags(dna, vector);
        if (matched.isEmpty()) {
            return "선택한 지역들과 경험 구성이 비슷해요.";
        }
        String joined = String.join("·", matched);
        String base = "좋았다고 한 여행의 '" + joined + "'" + subjectParticle(joined) + " 여기서도 같은 비중으로 나타나요.";
        String extra = distinctive(dna, vector);
        // 겹치는 것만 말하면 '비슷한 곳'이지 '발견'이 아니다. 새로운 축을 하나 덧붙인다.
        return extra == null ? base
                : base + " 여기에 '" + extra + "'" + subjectParticle(extra) + " 더해져요.";
    }

    /** 코스 추천 카드에 쓰는 근거 — 지역이 아니라 이 코스가 취향을 담았다고 말한다. */
    private static String courseReason(TravelerDna dna, Map<String, Double> vector) {
        List<String> matched = matchedTags(dna, vector);
        if (matched.isEmpty()) return "좋아했던 여행의 흐름과 닮은 공식 코스예요.";
        String joined = String.join("·", matched);
        return "좋았다고 한 여행의 '" + joined + "'" + subjectParticle(joined)
                + " 이 코스의 장면들에서도 이어져요.";
    }

    /**
     * 한 지역 안에서도 사용자 취향에 가장 가까운 공식 코스 하나를 고른다.
     * 지역 벡터는 발견 가능성을, 코스 벡터는 실제 경유지의 결을 나타낸다.
     */
    private CourseChoice bestCourse(TravelerDna dna, Map<String, Double> regionVector,
                                    List<TravelCourse> courses) {
        if (courses == null || courses.isEmpty()) return null;
        CourseChoice best = null;
        double regionSimilarity = cosine(dna.vector(), regionVector);
        for (TravelCourse course : courses) {
            Map<String, Double> cv = courseVector(course);
            // 코스 텍스트가 아직 빈약한 경우에만 지역 벡터를 폴백으로 쓴다.
            Map<String, Double> displayVector = cv.isEmpty() ? regionVector : cv;
            double courseSimilarity = cv.isEmpty() ? regionSimilarity : cosine(dna.vector(), cv);
            double similarity = cv.isEmpty()
                    ? regionSimilarity
                    : regionSimilarity * 0.35 + courseSimilarity * 0.65;
            if (best == null || similarity > best.similarity()) {
                best = new CourseChoice(course, displayVector, similarity);
            }
        }
        return best;
    }

    private boolean usableCourse(TravelCourse course) {
        return course != null
                && course.getSigCd() != null
                && course.getTitle() != null && !course.getTitle().isBlank()
                && course.getPoints() != null && !course.getPoints().isEmpty();
    }

    /** TourAPI 코스 제목·경유지 소제목·설명을 같은 12개 경험축으로 표현한다. */
    private Map<String, Double> courseVector(TravelCourse course) {
        StringBuilder text = new StringBuilder()
                .append(' ').append(ExperienceTags.lower(course.getTitle()))
                .append(' ').append(ExperienceTags.lower(course.getTheme()));
        for (CoursePoint point : course.getPoints()) {
            text.append(' ').append(ExperienceTags.lower(point.getName()))
                    .append(' ').append(ExperienceTags.lower(point.getDescription()))
                    .append(' ').append(ExperienceTags.lower(point.getType()));
        }
        Map<String, Integer> counts = ExperienceTags.countByTag(text.toString());
        Map<String, Double> weighted = new LinkedHashMap<>();
        Map<String, Double> idf = idf();
        counts.forEach((tag, n) -> weighted.put(tag, n * idf.getOrDefault(tag, 1.0)));
        return normalize(weighted);
    }

    /** 코스 API가 준 경유지 이미지 중 첫 장을 표지로 쓴다. */
    private static String courseImage(TravelCourse course) {
        return course.getPoints().stream()
                .map(CoursePoint::getImage)
                .filter(v -> v != null && !v.isBlank())
                .findFirst().orElse(null);
    }

    /** subname을 경유지 소제목으로 그대로 보존한다. */
    private static List<String> courseStops(TravelCourse course) {
        return course.getPoints().stream()
                .map(CoursePoint::getName)
                .filter(v -> v != null && !v.isBlank())
                .limit(4)
                .toList();
    }

    /** 첫 번째 subdetailoverview를 카드의 감성 문장으로 사용한다. */
    private static String courseSubtitle(TravelCourse course) {
        return course.getPoints().stream()
                .map(CoursePoint::getDescription)
                .filter(v -> v != null && !v.isBlank())
                .map(TravelerProfileService::plainText)
                .filter(v -> !v.isBlank())
                .findFirst()
                .map(TravelerProfileService::cardLength)
                .orElseGet(() -> courseStops(course).stream().findFirst().orElse("공식 여행 코스"));
    }

    private static String plainText(String value) {
        return value.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cardLength(String value) {
        final int max = 180;
        if (value.length() <= max) return value;
        return value.substring(0, max).stripTrailing() + "…";
    }

    /** 받침 유무에 따른 주격 조사 — '바다가' / '역사가' / '골목이' */
    private static String subjectParticle(String word) {
        if (word == null || word.isBlank()) return "가";
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return "가";     // 한글이 아니면 기본형
        return (last - 0xAC00) % 28 == 0 ? "가" : "이";
    }

    private static String sentence(List<TravelerDna.Axis> axes) {
        if (axes.isEmpty()) {
            return "아직 결을 읽기에는 입력이 부족해요.";
        }
        String a = axes.get(0).tag();
        String b = axes.size() > 1 ? axes.get(1).tag() : null;
        return b == null
                ? "'" + a + "'" + subjectParticle(a) + " 중심에 있는 여행"
                : "'" + a + "'" + andParticle(a) + " '" + b + "'" + subjectParticle(b) + " 이어지는 여행";
    }

    /** 받침 유무에 따른 접속 조사 — '바다와' / '골목과' */
    private static String andParticle(String word) {
        if (word == null || word.isBlank()) return "와";
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return "와";
        return (last - 0xAC00) % 28 == 0 ? "와" : "과";
    }

    private String regionName(String sigCd) {
        return regionRepository.findById(sigCd).map(Region::getName).orElse(sigCd);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private record CourseChoice(TravelCourse course, Map<String, Double> vector, double similarity) {
    }

    private record Scored(Region region, Map<String, Double> vector, double similarity,
                          long foodCount, TravelCourse course) {
    }
}
