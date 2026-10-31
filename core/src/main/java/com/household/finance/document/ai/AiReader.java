package com.household.finance.document.ai;

import java.io.IOException;

/**
 * Reads one bill or receipt photo with an AI vision model. Opt-in: a household must have this configured and the
 * user must ask for it on that one upload (see DocumentImportService). Implementations must send only the one
 * photo given, must never log the photo, the prompt or the model's answer, and must not store the photo.
 */
public interface AiReader {

    /** Whether this reader is configured to run at all (for example, an API key is set). No network call is made. */
    boolean enabled();

    /** @throws AiUnavailableException if called while {@link #enabled()} is false, or the service could not be reached. */
    AiReading read(byte[] image) throws IOException;
}
