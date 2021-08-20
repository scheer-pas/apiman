package io.apiman.manager.api.events;

/**
 * All Apiman events should implement this class.
 *
 * <p>Contains headers segment for various metadata that is useful to the event but does not pertain to the
 * semantics of the event itself (such as version, timestamps, etc.). This is mostly aligned with CloudEvents.
 *
 * @author Marc Savy {@literal <marc@blackparrotlabs.io>}
 */
public interface VersionedApimanEvent {

    /**
     * Get headers for this event
     */
    ApimanEventHeaders getHeaders();
}
