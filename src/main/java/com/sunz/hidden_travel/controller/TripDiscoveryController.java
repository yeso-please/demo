package com.sunz.hidden_travel.controller;

import com.sunz.hidden_travel.controller.dto.TripDiscoveryRequest;
import com.sunz.hidden_travel.controller.dto.TripRecommendationSet;
import com.sunz.hidden_travel.controller.dto.TripRoomView;
import com.sunz.hidden_travel.domain.SharedTripRoom;
import com.sunz.hidden_travel.service.PersonalizedTripService;
import com.sunz.hidden_travel.service.SharedTripService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;

@Controller
public class TripDiscoveryController {

    private final PersonalizedTripService personalizedTripService;
    private final SharedTripService sharedTripService;

    public TripDiscoveryController(PersonalizedTripService personalizedTripService,
                                   SharedTripService sharedTripService) {
        this.personalizedTripService = personalizedTripService;
        this.sharedTripService = sharedTripService;
    }

    /*
     * 제거됨 — GET /trip/new (조건 폼), GET /trip/recommendations (후보 3안).
     *
     * '맞춤 여행안'은 출발지·기간·동행·이동수단을 폼으로 묻고 후보 3개를 내놓던 이전 기획이다.
     * 지금은 다이어리에 쓴 글에서 취향을 읽어 지도에 불을 켜는 것이 유일한 입력이라,
     * 같은 일을 하는 화면이 둘이면 사용자는 어느 쪽이 진짜인지 알 수 없다.
     *
     * 동행자 공유방(/trips/*)은 그 폼에서만 들어갈 수 있었는데,
     * 이제 지도의 발견 카드에서 바로 연다(POST /trips/from-discovery).
     */

    /**
     * 지도에서 보고 있던 후보들을 그대로 '함께 고르기' 방으로 만든다.
     *
     * <p>방이 하는 일은 여러 후보를 놓고 의견을 모으는 것인데, 그 후보는 이제
     * 조건 폼이 아니라 <b>내가 쓴 글이 부른 곳</b>에서 나온다.
     */
    @PostMapping("/trips/from-discovery")
    public String createFromDiscovery(@RequestParam(defaultValue = "우리의 숨은 여행 후보") String title,
                                      @RequestParam("sigCd") List<String> sigCds,
                                      HttpSession session,
                                      RedirectAttributes ra) {
        try {
            SharedTripRoom room = sharedTripService.createFromDiscovery(title, sigCds);
            session.setAttribute(hostKey(room.getShareCode()), Boolean.TRUE);
            return "redirect:/trips/" + room.getShareCode();
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/map";
        }
    }

    @PostMapping("/trips")
    public String createRoom(
            @RequestParam(defaultValue = "우리의 숨은 여행 후보") String title,
            @RequestParam String departure,
            @RequestParam String duration,
            @RequestParam String companion,
            @RequestParam int people,
            @RequestParam String transport,
            @RequestParam(name = "experience", required = false) List<String> experiences,
            @RequestParam(defaultValue = "") String freeText,
            @RequestParam(name = "courseKey") List<String> courseKeys,
            HttpSession session) {
        SharedTripRoom room = sharedTripService.create(title,
                new TripDiscoveryRequest(departure, duration, companion, people, transport, experiences, freeText),
                courseKeys);
        session.setAttribute(hostKey(room.getShareCode()), Boolean.TRUE);
        return "redirect:/trips/" + room.getShareCode();
    }

    @GetMapping("/trips/{code}")
    public String room(@PathVariable String code, HttpSession session, Model model) {
        TripRoomView room = sharedTripService.view(code);
        model.addAttribute("view", room);
        model.addAttribute("isHost", Boolean.TRUE.equals(session.getAttribute(hostKey(code))));
        return "trip-room";
    }

    @PostMapping("/trips/{code}/reactions")
    public String react(@PathVariable String code,
                        @RequestParam String nickname,
                        @RequestParam String courseKey,
                        @RequestParam String reaction,
                        @RequestParam(defaultValue = "") String comment,
                        HttpSession session) {
        sharedTripService.react(code, nickname, courseKey, reaction, comment);
        session.setAttribute("tripNickname:" + code, nickname.trim());
        return "redirect:/trips/" + code + "#candidate-" + courseKey;
    }

    @GetMapping("/trips/{code}/summary")
    public String summary(@PathVariable String code, HttpSession session, Model model) {
        TripRoomView room = sharedTripService.view(code);
        model.addAttribute("view", room);
        model.addAttribute("isHost", Boolean.TRUE.equals(session.getAttribute(hostKey(code))));
        model.addAttribute("suggested", room.candidates().stream()
                .max(Comparator.comparingLong(v -> v.likeCount() * 2 + v.okayCount() - v.passCount()))
                .orElse(null));
        return "trip-summary";
    }

    @PostMapping("/trips/{code}/confirm")
    public String confirm(@PathVariable String code, @RequestParam String courseKey, HttpSession session) {
        if (!Boolean.TRUE.equals(session.getAttribute(hostKey(code)))) {
            return "redirect:/trips/" + code + "?hostRequired";
        }
        sharedTripService.confirm(code, courseKey);
        return "redirect:/trips/" + code + "/summary";
    }

    private static String hostKey(String code) {
        return "tripHost:" + code;
    }
}
