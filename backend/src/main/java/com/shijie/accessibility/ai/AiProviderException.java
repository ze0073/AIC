package com.shijie.accessibility.ai;

/** A provider failure that must not invalidate deterministic scan findings. */
public class AiProviderException extends RuntimeException {
    public AiProviderException(String message) { super(message); }
    public AiProviderException(String message, Throwable cause) { super(message, cause); }
}
