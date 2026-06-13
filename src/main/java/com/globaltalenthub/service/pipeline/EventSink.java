package com.globaltalenthub.service.pipeline;

/**
 * Sink for pipeline events. The SSE controller supplies an implementation that
 * writes to an {@code SseEmitter}; tests supply a collector. Lets the pipeline
 * orchestration ({@link SearchPipelineService}) stay free of transport concerns —
 * the Java analogue of the Node async generator's {@code yield}.
 */
@FunctionalInterface
public interface EventSink {

    /**
     * Emit one event.
     *
     * @param type    SSE event name (e.g. {@code company_enriched})
     * @param message human-readable status line
     * @param data    payload object (serialized to JSON by the transport), may be null
     */
    void emit(String type, String message, Object data);
}
