package com.sunz.hidden_travel.service;

import com.sunz.hidden_travel.controller.dto.CandidateReactionView;
import com.sunz.hidden_travel.controller.dto.TripCandidate;
import com.sunz.hidden_travel.controller.dto.TripDiscoveryRequest;
import com.sunz.hidden_travel.controller.dto.TripRecommendationSet;
import com.sunz.hidden_travel.controller.dto.TripRoomView;
import com.sunz.hidden_travel.domain.SharedTripRoom;
import com.sunz.hidden_travel.domain.TripReaction;
import com.sunz.hidden_travel.repository.SharedTripRoomRepository;
import com.sunz.hidden_travel.repository.TripReactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class SharedTripService {

    private static final Set<String> REACTIONS = Set.of("LIKE", "OKAY", "PASS");

    private final SharedTripRoomRepository roomRepository;
    private final TripReactionRepository reactionRepository;
    private final PersonalizedTripService personalizedTripService;

    public SharedTripService(SharedTripRoomRepository roomRepository,
                             TripReactionRepository reactionRepository,
                             PersonalizedTripService personalizedTripService) {
        this.roomRepository = roomRepository;
        this.reactionRepository = reactionRepository;
        this.personalizedTripService = personalizedTripService;
    }

    /**
     * 지도에서 발견한 지역들을 그대로 방으로 만든다 — <b>조건 폼 없이</b>.
     *
     * <p>예전에는 출발지·기간·동행·이동수단을 묻는 '맞춤 여행안' 화면에서만 방을 만들 수 있었다.
     * 그 화면을 걷어내면서 진입점이 사라졌는데, 정작 방이 하는 일(여러 후보를 놓고 의견을 모으는 것)은
     * <b>지도의 발견 카드가 이미 여러 후보를 갖고 있으므로</b> 그대로 성립한다.
     *
     * <p>묻지 않은 조건은 <b>비워 둔다.</b> 기본값('서울 출발 · 당일 · 자동차')을 채워 넣으면
     * 참여자에게는 방장이 정한 조건으로 보인다 — 아무도 말한 적 없는 사실이다.
     * 화면은 {@link TripRoomView#hasContext()} 로 그 줄을 통째로 숨긴다.
     *
     * @param sigCds 지도에서 보고 있던 후보 지역들 (2~3곳)
     */
    @Transactional
    public SharedTripRoom createFromDiscovery(String title, List<String> sigCds) {
        List<String> courseKeys = sigCds == null ? List.of()
                : sigCds.stream().filter(v -> v != null && !v.isBlank()).distinct()
                        .map(cd -> "assembled-" + cd + "-0").toList();
        if (courseKeys.size() < 2) {
            throw new IllegalArgumentException("의견을 모으려면 후보가 두 곳 이상이어야 해요.");
        }
        SharedTripRoom room = new SharedTripRoom();
        room.setShareCode(newCode());
        room.setTitle(title == null || title.isBlank() ? "우리의 숨은 여행 후보" : title.trim());
        room.setCandidateSigCds(String.join(",", courseKeys.stream().limit(3).toList()));
        return roomRepository.save(room);
    }

    @Transactional
    public SharedTripRoom create(String title, TripDiscoveryRequest request, List<String> courseKeys) {
        if (courseKeys == null || courseKeys.size() < 2) {
            throw new IllegalArgumentException("공유할 여행 후보가 부족합니다.");
        }
        SharedTripRoom room = new SharedTripRoom();
        room.setShareCode(newCode());
        room.setTitle(title == null || title.isBlank() ? "우리의 숨은 여행 후보" : title.trim());
        room.setDeparture(request.departure());
        room.setDuration(request.duration());
        room.setCompanion(request.companion());
        room.setPeople(request.people());
        room.setTransport(request.transport());
        room.setExperiencesCsv(String.join(",", request.experiences()));
        room.setFreeText(request.freeText());
        room.setCandidateSigCds(String.join(",", courseKeys.stream().distinct().limit(3).toList()));
        return roomRepository.save(room);
    }

    @Transactional(readOnly = true)
    public TripRoomView view(String code) {
        SharedTripRoom room = requireRoom(code);
        TripDiscoveryRequest request = requestOf(room);
        List<String> courseKeys = courseKeys(room);
                // 조건 폼에서 만든 방만 방향 딱지가 참이다(위 createFromDiscovery 주석 참고)
        TripRecommendationSet set = personalizedTripService.restore(request, courseKeys, hasContext(room));
        List<TripReaction> reactions = reactionRepository.findByRoomCodeOrderByUpdatedAtAsc(code);
        List<CandidateReactionView> candidateViews = set.candidates().stream().map(candidate -> {
            List<TripReaction> own = reactions.stream()
                    .filter(r -> candidate.courseKey().equals(r.getCandidateSigCd())
                            || candidate.sigCd().equals(r.getCandidateSigCd())).toList();
            return new CandidateReactionView(candidate, own,
                    own.stream().filter(r -> "LIKE".equals(r.getReaction())).count(),
                    own.stream().filter(r -> "OKAY".equals(r.getReaction())).count(),
                    own.stream().filter(r -> "PASS".equals(r.getReaction())).count());
        }).toList();
        List<String> participants = reactions.stream().map(TripReaction::getNickname)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        TripCandidate selected = set.candidates().stream()
                .filter(c -> c.courseKey().equals(room.getSelectedSigCd())
                        || c.sigCd().equals(room.getSelectedSigCd())).findFirst().orElse(null);
        return new TripRoomView(room, request, candidateViews, participants, selected);
    }

    @Transactional
    public void react(String code, String nickname, String courseKey, String reaction, String comment) {
        SharedTripRoom room = requireRoom(code);
        if ("CONFIRMED".equals(room.getStatus())) {
            throw new IllegalStateException("이미 확정된 여행방입니다.");
        }
        if (!containsCourse(room, courseKey)) throw new IllegalArgumentException("이 방의 코스 후보가 아닙니다.");
        String normalized = reaction == null ? "" : reaction.toUpperCase(Locale.ROOT);
        if (!REACTIONS.contains(normalized)) throw new IllegalArgumentException("지원하지 않는 반응입니다.");
        String member = nickname == null ? "" : nickname.trim();
        if (member.isBlank() || member.length() > 24) throw new IllegalArgumentException("닉네임을 확인해 주세요.");

        TripReaction value = reactionRepository.findByRoomCodeAndCandidateSigCdAndNickname(code, courseKey, member)
                .orElseGet(TripReaction::new);
        value.setRoomCode(code);
        value.setCandidateSigCd(courseKey);
        value.setNickname(member);
        value.setReaction(normalized);
        value.setComment(comment == null ? null : comment.trim().substring(0, Math.min(300, comment.trim().length())));
        value.setUpdatedAt(LocalDateTime.now());
        reactionRepository.save(value);
    }

    @Transactional
    public void confirm(String code, String courseKey) {
        SharedTripRoom room = requireRoom(code);
        if (!containsCourse(room, courseKey)) throw new IllegalArgumentException("이 방의 코스 후보가 아닙니다.");
        room.setSelectedSigCd(courseKey);
        room.setStatus("CONFIRMED");
        roomRepository.save(room);
    }

    private SharedTripRoom requireRoom(String code) {
        return roomRepository.findByShareCode(code)
                .orElseThrow(() -> new IllegalArgumentException("여행방을 찾을 수 없습니다."));
    }

    private String newCode() {
        String code;
        do code = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        while (roomRepository.existsByShareCode(code));
        return code;
    }

    private static TripDiscoveryRequest requestOf(SharedTripRoom room) {
        List<String> experiences = room.getExperiencesCsv() == null || room.getExperiencesCsv().isBlank()
                ? List.of() : Arrays.stream(room.getExperiencesCsv().split(",")).toList();
        return new TripDiscoveryRequest(room.getDeparture(), room.getDuration(), room.getCompanion(),
                room.getPeople(), room.getTransport(), experiences, room.getFreeText());
    }

    private static boolean hasContext(SharedTripRoom room) {
        return room.getDeparture() != null && !room.getDeparture().isBlank();
    }

    private static List<String> courseKeys(SharedTripRoom room) {
        return Arrays.stream(room.getCandidateSigCds().split(","))
                .filter(v -> !v.isBlank()).distinct().limit(3).toList();
    }

    private static boolean containsCourse(SharedTripRoom room, String candidateKey) {
        if (courseKeys(room).contains(candidateKey)) return true;
        if (candidateKey != null && candidateKey.startsWith("assembled-")) {
            String[] parts = candidateKey.split("-");
            return parts.length >= 3 && courseKeys(room).contains(parts[1]);
        }
        return false;
    }
}
