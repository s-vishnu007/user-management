package com.example.cp.scim;

import org.springframework.data.domain.AbstractPageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} keyed by an ABSOLUTE row offset rather than a page number, so SCIM's 1-based
 * {@code startIndex} (RFC 7644 §3.4.2.4) can be honored exactly even when it is not aligned to a page
 * boundary. Spring Data's {@link org.springframework.data.domain.PageRequest} only supports
 * {@code pageNumber * pageSize} offsets; an unaligned {@code startIndex} would otherwise be rounded to a
 * page boundary and return a shifted/overlapping window.
 */
public class OffsetPageRequest extends AbstractPageRequest {

    private final long offset;
    private final Sort sort;

    public OffsetPageRequest(long offset, int limit, Sort sort) {
        super(0, limit < 1 ? 1 : limit);
        this.offset = Math.max(offset, 0);
        this.sort = sort == null ? Sort.unsorted() : sort;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(offset + getPageSize(), getPageSize(), sort);
    }

    @Override
    public Pageable previous() {
        long prev = offset - getPageSize();
        return new OffsetPageRequest(prev < 0 ? 0 : prev, getPageSize(), sort);
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(0, getPageSize(), sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageRequest((long) pageNumber * getPageSize(), getPageSize(), sort);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OffsetPageRequest that)) return false;
        return offset == that.offset && getPageSize() == that.getPageSize() && sort.equals(that.sort);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(offset);
        result = 31 * result + getPageSize();
        result = 31 * result + sort.hashCode();
        return result;
    }
}
