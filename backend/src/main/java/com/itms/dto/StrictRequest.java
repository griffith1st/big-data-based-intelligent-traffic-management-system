package com.itms.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
/** Reject entity IDs, audit fields and misspelled fields instead of silently accepting them. */
public abstract class StrictRequest {
    @JsonAnySetter public final void unknownField(String name, Object value) {
        throw new IllegalArgumentException("请求包含未知字段: " + name);
    }
}
