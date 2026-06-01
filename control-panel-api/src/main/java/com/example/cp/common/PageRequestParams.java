package com.example.cp.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class PageRequestParams {

    private PageRequestParams() {}

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    public static Pageable of(Integer page, Integer size, String sort) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Sort sortObj = parseSort(sort);
        return PageRequest.of(p, s, sortObj);
    }

    public static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.unsorted();
        }
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        Sort.Direction dir = Sort.Direction.ASC;
        if (parts.length > 1) {
            String d = parts[1].trim();
            if (d.equalsIgnoreCase("desc")) {
                dir = Sort.Direction.DESC;
            }
        }
        return Sort.by(dir, property);
    }
}
