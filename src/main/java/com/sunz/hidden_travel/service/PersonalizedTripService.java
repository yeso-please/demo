package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.controller.dto.DayPlan;
import com.sunz.hidden_travel.controller.dto.TripCandidate;
import com.sunz.hidden_travel.controller.dto.TripDiscoveryRequest;
import com.sunz.hidden_travel.controller.dto.TripRecommendationSet;
import com.sunz.hidden_travel.domain.Attraction;
import com.sunz.hidden_travel.domain.CoursePoint;
import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.TravelCourse;
import com.sunz.hidden_travel.repository.AttractionRepository;
import com.sunz.hidden_travel.repository.FoodPlaceRepository;
import com.sunz.hidden_travel.repository.GoodPriceShopRepository;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.repository.TravelCourseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 학습 모델 투입 전의 설명 가능한 추천 베이스라인.
 * 상세 설명의 키워드, 출발지 거리, 코스 조립 가능성을 분리해 계산한다.
 */
@Service
public class PersonalizedTripService {


    private final RegionRepository regionRepository;
    private final AttractionRepository attractionRepository;
    private final FoodPlaceRepository foodPlaceRepository;
    private final GoodPriceShopRepository goodPriceShopRepository;
    private final TravelCourseRepository travelCourseRepository;
    private final DayPlanService dayPlanService;

    public PersonalizedTripService(RegionRepository regionRepository,
                                   AttractionRepository attractionRepository,
                                   FoodPlaceRepository foodPlaceRepository,
                                   GoodPriceShopRepository goodPriceShopRepository,
                                   TravelCourseRepository travelCourseRepository,
                                   DayPlanService dayPlanService) {
        this.regionRepository = regionRepository;
        this.attractionRepository = attractionRepository;
        this.foodPlaceRepository = foodPlaceRepository;
        this.goodPriceShopRepository = goodPriceShopRepository;
        this.travelCourseRepository = travelCourseRepository;
        this.dayPlanService = dayPlanService;
    }

    @Transactional(readOnly = true)
    public TripRecommendationSet recommend(TripDiscoveryRequest request) {
        Map<String, List<Attraction>> attractions = new HashMap<>();
        for (Attraction attraction : attractionRepository.findAll()) {
            attractions.computeIfAbsent(attraction.getSigCd(), ignored -> new ArrayList<>()).add(attraction);
        }
        Map<String, Long> foodCounts = counts(foodPlaceRepository.countBySigCd());
        Map<String, Long> shopCounts = counts(goodPriceShopRepository.countBySigCd());
        Region departure = findDeparture(request.departure());

        List<Profile> profiles = regionRepository.findAll().stream()
                .map(region -> profile(region, attractions.getOrDefault(region.getSigCd(), List.of()),
                        foodCounts.getOrDefault(region.getSigCd(), 0L),
                        shopCounts.getOrDefault(region.getSigCd(), 0L), request, departure))
                .filter(Profile::ready)
                .filter(profile -> !isDepartureArea(profile.region(), departure, request.departure()))
                .toList();

        List<Profile> byFit = profiles.stream()
                .sorted(Comparator.comparingDouble(Profile::fitScore).reversed()
                        .thenComparing(p -> p.region().getName()))
                .toList();

        List<TripCandidate> result = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        addFirstAvailable(result, used, byFit, request, "fit", "취향에 가까운 하루");

        List<Profile> byExpansion = profiles.stream()
                .filter(p -> !used.contains(p.region().getSigCd()))
                .sorted(Comparator.comparingDouble(Profile::expansionScore).reversed()
                        .thenComparing(p -> p.region().getName()))
                .toList();
        addFirstAvailable(result, used, byExpansion, request, "expand", "취향을 넓히는 하루");

        List<Profile> byBalance = profiles.stream()
                .filter(p -> !used.contains(p.region().getSigCd()))
                .sorted(Comparator.comparingDouble(Profile::balanceScore).reversed()
                        .thenComparingInt(p -> stableOrder(request, p.region().getSigCd())))
                .toList();
        addFirstAvailable(result, used, byBalance, request, "balance", "뜻밖의 지역에서 보내는 하루");

        // 데이터 편차로 특정 방향 후보를 못 만든 경우에도 화면은 세 장을 유지한다.
        if (result.size() < 3) {
            addUntilThree(result, used, byFit, request);
        }
        return new TripRecommendationSet(request, result);
    }

    @Transactional(readOnly = true)
    public TripRecommendationSet restore(TripDiscoveryRequest request, List<String> courseKeys) {
        return restore(request, courseKeys, true);
    }

    /**
     * @param labelDirections 후보에 '취향에 가까운/넓히는/뜻밖의' 방향 딱지를 붙일지.
     *
     * <p>이 딱지는 <b>조건 폼에서 그 의도로 뽑은 세 후보</b>에만 참이다. 그때는 첫째를 fit,
     * 둘째를 expand, 셋째를 balance 로 <i>골랐기 때문에</i> 순서가 곧 의미였다.
     * 지도 발견 카드에서 만든 방은 유사도 순으로 넘어올 뿐이라, 같은 딱지를 붙이면
     * 두 번째 후보를 "취향을 넓히는 하루"라고 <b>단정</b>하게 된다 — 아무도 그렇게 고르지 않았다.
     * 그런 방은 딱지를 비우고, 후보마다 이미 있는 근거 문장이 설명을 맡는다.
     */
    @Transactional(readOnly = true)
    public TripRecommendationSet restore(TripDiscoveryRequest request, List<String> courseKeys,
                                         boolean labelDirections) {
        List<TripCandidate> candidates = new ArrayList<>();
        String[] directions = {"fit", "expand", "balance"};
        String[] labels = labelDirections
                ? new String[]{"취향에 가까운 하루", "취향을 넓히는 하루", "뜻밖의 지역에서 보내는 하루"}
                : new String[]{null, null, null};
        for (int i = 0; i < Math.min(3, courseKeys.size()); i++) {
            String courseKey = courseKeys.get(i);
            TravelCourse official = officialCourse(courseKey);
            String sigCd = official != null ? official.getSigCd() : assembledSigCd(courseKey);
            Region region = regionRepository.findById(sigCd).orElse(null);
            if (region == null) continue;
            List<Attraction> attractions = attractionRepository.findBySigCd(region.getSigCd());
            Profile profile = profile(region, attractions,
                    foodPlaceRepository.findBySigCd(region.getSigCd()).size(),
                    goodPriceShopRepository.findBySigCd(region.getSigCd()).size(), request, findDeparture(request.departure()));
            int variant = assembledVariant(courseKey);
            DayPlan plan = official != null ? officialPlan(region, official) : dayPlanService.plan(region.getSigCd(), variant);
            if (plan.available()) candidates.add(toCandidate(profile, plan, official, request, directions[i], labels[i]));
        }
        return new TripRecommendationSet(request, candidates);
    }

    private void addFirstAvailable(List<TripCandidate> out, Set<String> used, List<Profile> profiles,
                                   TripDiscoveryRequest request, String direction, String label) {
        for (Profile profile : profiles) {
            if (used.contains(profile.region().getSigCd())) continue;
            TravelCourse official = bestOfficialCourse(profile, request);
            DayPlan plan = official != null ? officialPlan(profile.region(), official)
                    : dayPlanService.plan(profile.region().getSigCd(), 0);
            if (!plan.available()) continue;
            out.add(toCandidate(profile, plan, official, request, direction, label));
            used.add(profile.region().getSigCd());
            return;
        }
    }

    private void addUntilThree(List<TripCandidate> out, Set<String> used, List<Profile> profiles,
                               TripDiscoveryRequest request) {
        for (Profile profile : profiles) {
            if (out.size() >= 3) return;
            if (used.contains(profile.region().getSigCd())) continue;
            TravelCourse official = bestOfficialCourse(profile, request);
            DayPlan plan = official != null ? officialPlan(profile.region(), official)
                    : dayPlanService.plan(profile.region().getSigCd(), 0);
            if (!plan.available()) continue;
            out.add(toCandidate(profile, plan, official, request, "alternative", "또 다른 방향의 하루"));
            used.add(profile.region().getSigCd());
        }
    }

    private TripCandidate toCandidate(Profile p, DayPlan plan, TravelCourse official, TripDiscoveryRequest request,
                                      String direction, String label) {
        List<String> tags = p.tags().isEmpty() ? List.of("지역 산책", "로컬 미식") : p.tags().stream().limit(3).toList();
        String title = official != null && official.getTitle() != null && !official.getTitle().isBlank()
                ? official.getTitle() : narrative(tags, p.region().getName());
        String reason = switch (direction) {
            case "fit" -> selectedReason(request, tags, "선택한 경험과 지역 데이터가 가장 많이 겹쳐요.");
            case "expand" -> selectedReason(request, tags, "익숙한 취향을 유지하면서 다른 결을 하나 더해요.");
            case "balance" -> "코스를 만들 데이터는 충분하지만 이번 추천군에서 덜 반복된 지역이에요.";
            default -> "선택한 조건 안에서 실제 하루 코스를 구성할 수 있어요.";
        };
        String newPoint = tags.stream().filter(t -> !request.experiences().contains(t)).findFirst()
                .map(t -> "기존 선택에 없던 ‘" + t + "’ 경험이 더해져요.")
                .orElse("장소 간 구성을 달리해 익숙한 취향을 새로운 지역에서 경험해요.");
        String image = plan.stops().stream().map(DayPlan.Stop::image)
                .filter(v -> v != null && !v.isBlank()).findFirst()
                .orElseGet(() -> p.attractions().stream().map(Attraction::getImage)
                        .filter(v -> v != null && !v.isBlank()).findFirst().orElse(null));
        String warning = plan.hasHoursGap() ? "일부 운영시간 미검증" : "공공데이터 기준";
        int goodPrice = (int) plan.stops().stream().filter(DayPlan.Stop::sage).count();
        String courseKey = official != null ? "official-" + official.getId()
                : "assembled-" + p.region().getSigCd() + "-" + plan.variant();
        String detailUrl = official != null
                ? "/course?sigCd=" + p.region().getSigCd() + "&courseId=" + official.getId()
                : "/course?sigCd=" + p.region().getSigCd() + "&auto=true&variant=" + plan.variant();
        return new TripCandidate(courseKey, official != null ? official.getId() : null,
                direction, label, p.region().getSigCd(), p.region().getName(),
                p.region().getProvince(), title, image, tags, reason, newPoint, request.duration(),
                plan.stops().size(), plan.distanceText(), official != null ? "TourAPI 공식 코스" : "자동 조립 코스",
                warning, goodPrice, p.region().getLat(), p.region().getLng(), detailUrl, plan);
    }

    private TravelCourse bestOfficialCourse(Profile profile, TripDiscoveryRequest request) {
        return travelCourseRepository.findBySigCd(profile.region().getSigCd()).stream()
                .filter(course -> course.getPoints() != null && course.getPoints().size() >= 2)
                .max(Comparator.comparingDouble(course -> courseMatchScore(course, request)))
                .orElse(null);
    }

    private double courseMatchScore(TravelCourse course, TripDiscoveryRequest request) {
        StringBuilder text = new StringBuilder((safe(course.getTitle()) + " " + safe(course.getTheme())).toLowerCase(Locale.ROOT));
        course.getPoints().forEach(point -> text.append(' ').append(safe(point.getName()))
                .append(' ').append(safe(point.getType())).append(' ').append(safe(point.getDescription())));
        long experienceMatches = request.experiences().stream()
                .filter(tag -> ExperienceTags.words().getOrDefault(tag, List.of(tag)).stream().anyMatch(text.toString()::contains))
                .count();
        long freeMatches = tokens(request.freeText()).stream().filter(text.toString()::contains).count();
        return experienceMatches * 5 + freeMatches * 2 + Math.min(5, course.getPoints().size()) * .2;
    }

    private DayPlan officialPlan(Region region, TravelCourse course) {
        List<DayPlan.Stop> stops = new ArrayList<>();
        List<String> noCoords = new ArrayList<>();
        int verified = 0;
        int checkable = 0;
        for (int i = 0; i < course.getPoints().size(); i++) {
            CoursePoint point = course.getPoints().get(i);
            Attraction matched = point.getContentId() == null || point.getContentId().isBlank()
                    ? null : attractionRepository.findFirstBySourceContentId(point.getContentId()).orElse(null);
            if (matched != null) checkable++;
            boolean hoursVerified = matched != null && matched.getUsetime() != null && !matched.getUsetime().isBlank();
            if (hoursVerified) verified++;
            Double lat = matched != null ? matched.getLat() : null;
            Double lng = matched != null ? matched.getLng() : null;
            if (lat == null || lng == null) noCoords.add(point.getName());
            OpeningHours.Parsed oh = OpeningHours.parse(matched != null ? matched.getUsetime() : null);
            stops.add(new DayPlan.Stop(i + 1, courseSlot(i, course.getPoints().size()), point.getName(), "course",
                    point.getType() == null || point.getType().isBlank() ? "공식 코스 경유지" : point.getType(),
                    false, matched != null ? matched.getId() : null,
                    matched != null && matched.getImage() != null ? matched.getImage() : point.getImage(),
                    matched != null ? matched.getAddr() : null, lat, lng, null,
                    matched != null ? matched.getUsetime() : null, hoursVerified,
                    // 공식 코스는 경유지별 체류·이동 추정을 하지 않는다 —
                    // 순서만 주어질 뿐 좌표가 28% 만 매칭돼(DATA §9) 시간을 계산할 근거가 없다.
                    0, 0,
                    oh.alwaysOpen(), oh.openMinutes(), oh.closeMinutes(), oh.closedWeekday(),
                    matched != null ? matched.getDescription() : point.getDescription()));
        }
        String title = course.getTitle() == null || course.getTitle().isBlank()
                ? region.getName() + " 공식 여행코스" : course.getTitle();
        List<String> reasons = List.of(
                "TourAPI에 등록된 공식 코스의 경유지 " + stops.size() + "곳을 그대로 연결했어요.",
                "코스 제목과 경유지 설명을 선택한 여행 취향과 비교했어요.");
        String distance = course.getTotalDistance() == null || course.getTotalDistance().isBlank()
                ? "경로 확인 필요" : course.getTotalDistance();
        return new DayPlan(region.getSigCd(), region.getName(), region.getProvince(), title, 0,
                !stops.isEmpty(), stops.isEmpty() ? "공식 코스에 경유지가 없습니다." : null,
                // 표지는 경유지 중 사진이 있는 첫 곳 — 없으면 화면이 표지를 뺀다
                stops.stream().map(DayPlan.Stop::image)
                        .filter(img -> img != null && !img.isBlank()).findFirst().orElse(null),
                stops, reasons, distance, "공식 코스", null, noCoords, verified, checkable,
                0, null);
    }

    private TravelCourse officialCourse(String courseKey) {
        if (courseKey == null || !courseKey.startsWith("official-")) return null;
        try {
            return travelCourseRepository.findById(Long.parseLong(courseKey.substring("official-".length())))
                    .filter(course -> course.getPoints() != null && !course.getPoints().isEmpty()).orElse(null);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String assembledSigCd(String courseKey) {
        if (courseKey != null && courseKey.startsWith("assembled-")) {
            String[] parts = courseKey.split("-");
            if (parts.length >= 3) return parts[1];
        }
        // 기존 공유방은 지역 코드만 저장했으므로 자동 조립 0번 코스로 복원한다.
        return courseKey == null ? "" : courseKey;
    }

    private static int assembledVariant(String courseKey) {
        if (courseKey != null && courseKey.startsWith("assembled-")) {
            String[] parts = courseKey.split("-");
            if (parts.length >= 3) {
                try { return Integer.parseInt(parts[2]); }
                catch (NumberFormatException ignored) { return 0; }
            }
        }
        return 0;
    }

    private static String courseSlot(int index, int size) {
        if (size <= 2) return index == 0 ? "첫 번째" : "두 번째";
        return switch (index) {
            case 0 -> "오전";
            case 1 -> "점심 전";
            case 2 -> "오후";
            case 3 -> "저녁 전";
            default -> (index + 1) + "번째";
        };
    }

    private Profile profile(Region region, List<Attraction> attractions, long foodCount, long shopCount,
                            TripDiscoveryRequest request, Region departure) {
        StringBuilder text = new StringBuilder((region.getName() + " " + safe(region.getAiSummary())).toLowerCase(Locale.ROOT));
        for (Attraction a : attractions) {
            text.append(' ').append(safe(a.getName())).append(' ').append(safe(a.getDescription()))
                    .append(' ').append(safe(a.getType()));
        }
        // 태그 사전은 ExperienceTags 로 단일화했다(TravelerProfileService 와 같은 축을 써야 비교가 성립).
        List<String> tags = ExperienceTags.match(text.toString());
        long selectedMatches = request.experiences().stream().filter(tags::contains).count();
        long freeMatches = tokens(request.freeText()).stream().filter(text.toString()::contains).count();
        double distance = departure == null ? 120 : distanceKm(departure, region);
        double distancePenalty = distance / ("대중교통".equals(request.transport()) ? 170.0 : 260.0);
        double readiness = Math.min(4, attractions.size() / 10.0) + Math.min(2, foodCount / 15.0) + Math.min(1, shopCount / 10.0);
        double fit = selectedMatches * 8 + freeMatches * 3 + readiness - distancePenalty;
        long novelty = tags.stream().filter(t -> !request.experiences().contains(t)).count();
        double expansion = fit * .72 + Math.min(3, novelty) * 2.2;
        double balance = readiness * 3 - Math.max(0, attractions.size() - 35) * .08 - distancePenalty * .5;
        boolean ready = attractions.size() >= 2 && foodCount + shopCount > 0;
        return new Profile(region, attractions, tags, ready, fit, expansion, balance);
    }

    private Region findDeparture(String value) {
        if (value == null || value.isBlank()) return null;
        String needle = value.replace(" ", "");
        return regionRepository.findAll().stream()
                .filter(r -> (safe(r.getProvince()) + safe(r.getName())).replace(" ", "").contains(needle)
                        || needle.contains(safe(r.getName()).replace(" ", "")))
                .findFirst().orElse(null);
    }

    private static String selectedReason(TripDiscoveryRequest request, List<String> tags, String fallback) {
        List<String> common = tags.stream().filter(request.experiences()::contains).limit(2).toList();
        return common.isEmpty() ? fallback : "선택한 ‘" + String.join("·", common) + "’ 경험이 실제 장소 구성에서 확인돼요.";
    }

    private static String narrative(List<String> tags, String regionName) {
        String a = tags.isEmpty() ? "지역" : tags.get(0);
        String b = tags.size() > 1 ? tags.get(1) : "로컬 미식";
        return a + conjunction(a) + b + objectParticle(b) + " 잇는 " + regionName + "의 하루";
    }

    private static String conjunction(String word) { return hasBatchim(word) ? "과 " : "와 "; }
    private static String objectParticle(String word) { return hasBatchim(word) ? "을" : "를"; }
    private static boolean hasBatchim(String word) {
        if (word == null || word.isBlank()) return true;
        char last = word.charAt(word.length() - 1);
        return last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 != 0;
    }

    private static boolean isDepartureArea(Region candidate, Region departure, String rawDeparture) {
        if (departure == null) return false;
        if (candidate.getSigCd().equals(departure.getSigCd())) return true;
        String raw = rawDeparture == null ? "" : rawDeparture.replace(" ", "");
        String province = safe(departure.getProvince()).replace(" ", "");
        boolean provinceInput = !raw.isBlank() && (province.contains(raw) || raw.contains(province));
        return provinceInput && departure.getProvince().equals(candidate.getProvince());
    }

    private static List<String> tokens(String input) {
        if (input == null || input.isBlank()) return List.of();
        return List.of(input.toLowerCase(Locale.ROOT).split("[^가-힣a-z0-9]+"))
                .stream().filter(v -> v.length() >= 2).distinct().limit(8).toList();
    }

    private static int stableOrder(TripDiscoveryRequest request, String sigCd) {
        return Math.floorMod((request.departure() + request.experiences() + request.freeText() + sigCd).hashCode(), 10_000);
    }

    private static Map<String, Long> counts(List<Object[]> rows) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] row : rows) out.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        return out;
    }

    private static double distanceKm(Region a, Region b) {
        if (a.getLat() == null || a.getLng() == null || b.getLat() == null || b.getLng() == null) return 120;
        double dLat = Math.toRadians(b.getLat() - a.getLat());
        double dLng = Math.toRadians(b.getLng() - a.getLng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.getLat())) * Math.cos(Math.toRadians(b.getLat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private static String safe(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    private record Profile(Region region, List<Attraction> attractions, List<String> tags, boolean ready,
                           double fitScore, double expansionScore, double balanceScore) {}
}
