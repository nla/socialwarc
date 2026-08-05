package org.netpreserve.socialwarc.twitter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Error(String message) {
}
