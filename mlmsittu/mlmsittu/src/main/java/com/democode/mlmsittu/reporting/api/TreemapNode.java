package com.democode.mlmsittu.reporting.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * The treemap feed: category → customer → value (architecture §6.4).
 *
 * <p>Two levels of nesting, flattened into one recursive shape so the frontend hands it straight to
 * {@code d3.hierarchy} without reshaping. Leaves carry {@code value}; branches carry children and
 * leave {@code value} null, because a branch's area is the sum of its leaves and computing it in
 * two places invites the two answers to differ.
 *
 * @param value net sales attributed to this leaf. Null on a branch.
 * @param daysSinceLastOrder drives the colour scale — area encodes value, colour encodes recency.
 *     Null on a branch.
 */
public record TreemapNode(
        String name,
        BigDecimal value,
        Integer daysSinceLastOrder,
        List<TreemapNode> children) {

    public static TreemapNode branch(String name, List<TreemapNode> children) {
        return new TreemapNode(name, null, null, children);
    }

    public static TreemapNode leaf(String name, BigDecimal value, Integer daysSinceLastOrder) {
        return new TreemapNode(name, value, daysSinceLastOrder, List.of());
    }
}
