package com.example.cp.common;

import org.springframework.data.domain.Page;

import java.util.List;

public record PagedResponse<T>(List<T> items, long total, int page, int size) {

    public static <T> PagedResponse<T> from(Page<T> p) {
        return new PagedResponse<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize());
    }

    public static <T> PagedResponse<T> of(List<T> items, long total, int page, int size) {
        return new PagedResponse<>(items, total, page, size);
    }
}
