package com.household.finance.statement;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

/** Parses one bank's CSV export into statement lines. Pure: no database access. */
public interface StatementParser {

    /** @throws StatementParseException if the header or any row does not match the expected format */
    List<StatementLine> parse(Reader csv) throws IOException;
}
