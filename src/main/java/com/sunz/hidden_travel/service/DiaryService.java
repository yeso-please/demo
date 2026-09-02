package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.controller.dto.DiaryCard;
import com.sunz.hidden_travel.controller.dto.DiaryDetail;
import com.sunz.hidden_travel.controller.dto.PublicMap;
import com.sunz.hidden_travel.controller.dto.VisitInput;
import com.sunz.hidden_travel.domain.AppUser;
import com.sunz.hidden_travel.domain.Region;
import com.sunz.hidden_travel.domain.SavedCourse;
import com.sunz.hidden_travel.domain.SavedCourseStop;
import com.sunz.hidden_travel.repository.AppUserRepository;
import com.sunz.hidden_travel.repository.RegionRepository;
import com.sunz.hidden_travel.repository.SavedCourseRepository;
import com.sunz.hidden_travel.domain.Diary;
import com.sunz.hidden_travel.domain.Review;
import com.sunz.hidden_travel.repository.DiaryRepository;
import com.sunz.hidden_travel.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 다이어리 저장소.
 *
 * <p><b>비로그인은 세션, 로그인은 DB.</b> 다이어리는 로그인을 요구하지 않으므로
 * (첫 가치 경험을 로그인 뒤로 미루면 그 자리에서 이탈한다) 두 저장소를 다 쓴다.
 * 로그인하는 순간 {@link #adopt} 가 세션에 있던 편을 사용자에게 옮긴다.
 */
@Service
public class DiaryService {

    private static final Logger log = LoggerFactory.getLogger(DiaryService.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final int EXCERPT_LEN = 110;

    /** 만족도 코드를 사람 말로. 값이 없으면(옮겨온 후기) 아무 말도 하지 않는다 */
    private static final Map<String, String> SATISFACTION = Map.of(
            "again", "또 가고 싶다",
            "good", "좋았다",
            "soso", "그저 그랬다",
            "bad", "나와 안 맞았다");

    private final DiaryRepository diaryRepository;
    private final ReviewRepository reviewRepository;
    private final RegionRepository regionRepository;
    private final AppUserRepository appUserRepository;
    private final SavedCourseRepository savedCourseRepository;

    public DiaryService(DiaryRepository diaryRepository,
                        ReviewRepository reviewRepository,
                        RegionRepository regionRepository,
                        AppUserRepository appUserRepository,
                        SavedCourseRepository savedCourseRepository) {
        this.diaryRepository = diaryRepository;
        this.reviewRepository = reviewRepository;
        this.regionRepository = regionRepository;
        this.appUserRepository = appUserRepository;
        this.savedCourseRepository = savedCourseRepository;
    }

    /* =========================================================
       피드 · 상세
       ========================================================= */

    /** 다이어리 피드 — 공개된 편만 최신순 */
    @Transactional(readOnly = true)
    public List<DiaryCard> feed() {
        List<DiaryCard> cards = new ArrayList<>();
        for (Diary d : diaryRepository.findTop50BySharedTrueOrderByCreatedAtDesc()) {
            cards.add(new DiaryCard(
                    d.getId(),
                    nicknameOf(d.getUserId()),
                    d.getSigCd(),
                    regionLabel(d.getSigCd()),
                    courseTitle(d.getSavedCourseId()),
                    d.getWhenText(),
                    cover(d),
                    d.getPhotoPaths().size(),
                    excerpt(d.getText()),
                    d.tags(),
                    format(d.getCreatedAt())));
        }
        return cards;
    }

    /**
     * 한 편의 상세. 없으면 null.
     * 비공개 편은 <b>작성자에게만</b> 보인다 — 링크를 알아도 남은 볼 수 없다.
     */
    @Transactional(readOnly = true)
    public DiaryDetail detail(Long diaryId, Long viewerId) {
        Diary d = diaryRepository.findById(diaryId).orElse(null);
        if (d == null) return null;
        boolean mine = d.getUserId().equals(viewerId);
        if (!d.isShared() && !mine) return null;

        SavedCourse course = d.getSavedCourseId() == null ? null
                : savedCourseRepository.findById(d.getSavedCourseId()).orElse(null);
        return new DiaryDetail(
                d.getId(),
                nicknameOf(d.getUserId()),
                d.getSigCd(),
                regionLabel(d.getSigCd()),
                course != null ? course.getTitle() : null,
                course != null ? course.getStops().stream().map(SavedCourseStop::getName).toList() : List.of(),
                d.getWhenText(),
                // 옮겨온 후기에는 만족도가 없다 — 그 경우 null 이고 화면이 그 줄을 뺀다.
                // Map.of() 는 null 키 조회에서 NPE 를 던지므로 먼저 막는다.
                satisfactionLabel(d.getSatisfaction()),
                List.copyOf(d.getPhotoPaths()),
                d.getText(),
                d.tags(),
                format(d.getCreatedAt()),
                d.isShared(),
                mine);
    }

    private static String satisfactionLabel(String code) {
        return code == null ? null : SATISFACTION.get(code);
    }

    /**
     * 남에게 보여주는 지도. 없는 사람이면 null.
     *
     * <p>공개한 편만 본다 — 비공개 편은 글도 안 보이고 지도의 불도 안 켜진다.
     */
    @Transactional(readOnly = true)
    public PublicMap publicMap(String nickname, int totalRegions) {
        AppUser user = nickname == null ? null
                : appUserRepository.findByNickname(nickname).orElse(null);
        if (user == null) return null;

        List<Diary> shared = diaryRepository.findByUserIdAndSharedTrueOrderByCreatedAtDesc(user.getId());

        List<PublicMap.Lit> lit = new ArrayList<>();
        Map<String, Integer> tagCount = new LinkedHashMap<>();
        List<DiaryCard> cards = new ArrayList<>();

        for (Diary d : shared) {
            // 지도에 못 붙는 편은 공개 지도에서 통째로 뺀다.
            // 남기면 "3곳 밝혔어요"라고 써놓고 불은 2개만 켜져 숫자와 그림이 어긋난다.
            // (저장 단계에서 막고 있지만, 규칙이 생기기 전에 쓴 편이 남아 있다)
            if (!knownRegion(d.getSigCd())) continue;

            lit.add(new PublicMap.Lit(d.getSigCd(), regionLabel(d.getSigCd()), d.getId()));
            for (String t : d.tags()) tagCount.merge(t, 1, Integer::sum);
            cards.add(new DiaryCard(
                    d.getId(), user.getNickname(), d.getSigCd(), regionLabel(d.getSigCd()),
                    courseTitle(d.getSavedCourseId()), d.getWhenText(),
                    cover(d), d.getPhotoPaths().size(), excerpt(d.getText()), d.tags(),
                    format(d.getCreatedAt())));
        }

        List<String> topTags = tagCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5).map(Map.Entry::getKey).toList();

        return new PublicMap(user.getNickname(), lit.size(), totalRegions, lit, topTags, cards);
    }

    private String nicknameOf(Long userId) {
        AppUser u = userId == null ? null : appUserRepository.findById(userId).orElse(null);
        return u != null ? u.getNickname() : "여행자";
    }

    private String regionLabel(String sigCd) {
        Region r = sigCd == null ? null : regionRepository.findById(sigCd).orElse(null);
        // 모르는 코드라도 카드 제목이 비면 안 된다 — 제목 없는 카드는 깨진 카드다
        return r != null ? r.getProvince() + " " + r.getName() : "알 수 없는 지역";
    }

    /**
     * 우리가 아는 지역인지.
     *
     * <p>다이어리 API 는 시군구 코드를 그대로 받는데, 없는 코드로 쓴 편은
     * <b>지도에 영원히 불이 안 켜진다</b> — 조용히 사라지는 대신 저장 단계에서 막는다.
     * (실제로 겪었다: 전북 45→52 코드 변경 때 52130 으로 쓴 편이 지도에도 피드에도 못 붙었다)
     */
    @Transactional(readOnly = true)
    public boolean knownRegion(String sigCd) {
        return sigCd != null && regionRepository.existsById(sigCd);
    }

    private String courseTitle(Long savedCourseId) {
        if (savedCourseId == null) return null;
        return savedCourseRepository.findById(savedCourseId).map(SavedCourse::getTitle).orElse(null);
    }

    private static String cover(Diary d) {
        return d.getPhotoPaths().isEmpty() ? null : d.getPhotoPaths().get(0);
    }

    private static String format(LocalDateTime at) {
        return at != null ? at.format(DATE) : "";
    }

    private static String excerpt(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\s+", " ").trim();
        return flat.length() <= EXCERPT_LEN ? flat : flat.substring(0, EXCERPT_LEN) + "…";
    }

    @Transactional(readOnly = true)
    public List<Diary> mine(Long userId) {
        return userId == null ? List.of() : diaryRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 다이어리를 {@link VisitInput} 으로 — 취향 계산은 세션이든 DB든 같은 입력을 본다.
     *
     * <p>지도에 못 붙는 지역은 여기서 한 번에 걸러낸다. 화면마다 따로 거르면
     * 어떤 화면은 세고 어떤 화면은 안 세서 숫자가 서로 어긋난다 — 실제로 그랬다.
     */
    @Transactional(readOnly = true)
    public List<VisitInput> asVisits(Long userId) {
        return mine(userId).stream()
                .filter(d -> knownRegion(d.getSigCd()))
                .map(d -> new VisitInput(d.getSigCd(), d.getSatisfaction(), d.getText(),
                        d.tags(), d.getWhenText()))
                .toList();
    }

    /**
     * 한 편 저장(같은 지역은 덮어쓴다).
     *
     * <p>같은 지역을 여러 번 다녀올 수 있지만, 지금 화면은 지역당 한 편을 전제로
     * 목록을 그린다. 여러 편을 허용하려면 화면이 먼저 그걸 보여줄 수 있어야 한다.
     */
    @Transactional
    public Diary save(Long userId, VisitInput in, Long savedCourseId) {
        Diary d = diaryRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(x -> x.getSigCd().equals(in.sigCd()))
                .findFirst()
                .orElseGet(Diary::new);
        d.setUserId(userId);
        d.setSigCd(in.sigCd());
        d.setText(in.note());
        d.setTags(in.tags());
        d.setSatisfaction(in.satisfaction());
        d.setWhenText(in.when());
        if (savedCourseId != null) d.setSavedCourseId(savedCourseId);
        return diaryRepository.save(d);
    }

    /** 세션에 쌓인 편들을 로그인한 사용자에게 옮긴다 */
    @Transactional
    public int adopt(Long userId, List<VisitInput> fromSession) {
        if (userId == null || fromSession == null || fromSession.isEmpty()) return 0;
        int n = 0;
        for (VisitInput v : fromSession) {
            if (v == null || v.sigCd() == null || v.sigCd().isBlank()) continue;
            save(userId, v, null);
            n++;
        }
        return n;
    }

    /**
     * 기존 후기를 다이어리로 옮긴다(1회성).
     *
     * <p>후기는 이미 '다녀와서 쓴 글'이라 다이어리의 한 종류다 — 코스가 붙어 있다는 점만 다르다.
     * 원본 {@code review} 테이블은 지우지 않는다. 옮기다 잘못돼도 되돌릴 수 있어야 한다.
     *
     * <p>만족도는 <b>비워 둔다.</b> 후기에는 그 항목이 없었으니 추측해 넣으면
     * 취향 계산이 사용자가 말하지 않은 값을 근거로 삼게 된다.
     *
     * @return 옮긴 편 수
     */
    @Transactional
    public int importReviews() {
        int n = 0;
        for (Review r : reviewRepository.findAll()) {
            if (r.getUserId() == null || r.getSigCd() == null) continue;
            if (diaryRepository.existsByUserIdAndSigCd(r.getUserId(), r.getSigCd())) continue;
            Diary d = new Diary();
            d.setUserId(r.getUserId());
            d.setSigCd(r.getSigCd());
            d.setText(r.getContent());
            d.setTags(ExperienceTags.tagsFrom(r.getContent()));   // 글에서 다시 읽는다
            d.setSavedCourseId(r.getSavedCourseId());
            d.setShared(r.isShared());
            d.setPhotoPaths(List.copyOf(r.getPhotoPaths()));
            d.setCreatedAt(r.getCreatedAt());
            diaryRepository.save(d);
            n++;
        }
        if (n > 0) log.info("[Diary] 기존 후기 {}편을 다이어리로 옮겼습니다(원본은 그대로 둡니다).", n);
        return n;
    }
}
