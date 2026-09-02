package com.sunz.hidden_travel.controller.dto;

import com.sunz.hidden_travel.domain.TripReaction;

import java.util.List;

public record CandidateReactionView(
        TripCandidate candidate,
        List<TripReaction> reactions,
        long likeCount,
        long okayCount,
        long passCount
) {
    public long positiveCount() { return likeCount + okayCount; }
}
