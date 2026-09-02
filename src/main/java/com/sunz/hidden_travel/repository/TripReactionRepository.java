package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.TripReaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripReactionRepository extends JpaRepository<TripReaction, Long> {
    List<TripReaction> findByRoomCodeOrderByUpdatedAtAsc(String roomCode);
    Optional<TripReaction> findByRoomCodeAndCandidateSigCdAndNickname(String roomCode, String candidateSigCd, String nickname);
}
