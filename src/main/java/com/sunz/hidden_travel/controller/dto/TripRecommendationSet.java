package com.sunz.hidden_travel.controller.dto;

import java.util.List;

public record TripRecommendationSet(
        TripDiscoveryRequest request,
        List<TripCandidate> candidates
) {}
