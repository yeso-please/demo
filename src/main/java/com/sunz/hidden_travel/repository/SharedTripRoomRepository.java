package com.sunz.hidden_travel.repository;

import com.sunz.hidden_travel.domain.SharedTripRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SharedTripRoomRepository extends JpaRepository<SharedTripRoom, Long> {
    Optional<SharedTripRoom> findByShareCode(String shareCode);
    boolean existsByShareCode(String shareCode);
}
