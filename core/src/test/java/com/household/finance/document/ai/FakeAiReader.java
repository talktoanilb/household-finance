package com.household.finance.document.ai;

import java.io.IOException;

/** Test double: returns a fixed reading, or throws, without any network call. */
public class FakeAiReader implements AiReader {

    public AiReading nextReading;
    public RuntimeException nextFailure;
    public boolean enabled = true;
    public byte[] lastImageSeen;
    public int callCount;

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public AiReading read(byte[] image) throws IOException {
        callCount++;
        lastImageSeen = image;
        if (nextFailure != null) {
            throw nextFailure;
        }
        if (nextReading == null) {
            throw new AiUnavailableException("No reading set on the fake.");
        }
        return nextReading;
    }
}
