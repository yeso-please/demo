package com.sunz.hidden_travel.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 기존 지역 코드 컬럼을 코스 키 저장에 사용할 수 있도록 데이터 손실 없이 확장한다. */
@Component
public class TripCourseKeySchemaMigration {

    private final JdbcTemplate jdbcTemplate;

    public TripCourseKeySchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void widenLegacyCandidateColumns() {
        jdbcTemplate.execute("ALTER TABLE shared_trip_room ALTER COLUMN candidate_sig_cds VARCHAR(500)");
        jdbcTemplate.execute("ALTER TABLE shared_trip_room ALTER COLUMN selected_sig_cd VARCHAR(64)");
        jdbcTemplate.execute("ALTER TABLE trip_reaction ALTER COLUMN candidate_sig_cd VARCHAR(64)");
    }
}
