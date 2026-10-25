package com.household.finance.statement;

import java.util.List;

/**
 * What the app thinks a new CSV layout means, in terms the user can check.
 * {@code complete} is false when the app could not decide (then {@code layout} is null and the questions say why).
 * {@code preview} shows the first rows exactly as they would be imported. Nothing is saved until the user confirms.
 */
public record LayoutProposal(
        boolean complete,
        CsvLayout layout,
        List<String> columnNames,
        List<String> questions,
        List<String> preview) {}
